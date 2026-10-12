package com.payflow.payment.controller;

import com.payflow.payment.support.KafkaIntegrationSupport;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payflow.payment.repository.PaymentIdempotencyRepository;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.service.PaymentCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "payflow.payment-cache.ttl-seconds=2",
        "payflow.rate-limit.create-limit=3",
        "payflow.rate-limit.create-window-seconds=3",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms"})
@MockitoSpyBean(types = PaymentRepository.class)
@Testcontainers
class PaymentRedisIntegrationTest extends KafkaIntegrationSupport {

    private static final String PATH = "/api/v1/payments";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("payflow_redis_test")
            .withUsername("payflow_test")
            .withPassword("test_only_password");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    private final TestRestTemplate http;
    private final ObjectMapper mapper;
    private final PaymentRepository payments;
    private final PaymentIdempotencyRepository idempotency;
    private final StringRedisTemplate strings;
    private final PaymentCacheService cache;

    @Autowired
    PaymentRedisIntegrationTest(TestRestTemplate http, ObjectMapper mapper, PaymentRepository payments,
            PaymentIdempotencyRepository idempotency, StringRedisTemplate strings, PaymentCacheService cache) {
        this.http = http;
        this.mapper = mapper;
        this.payments = payments;
        this.idempotency = idempotency;
        this.strings = strings;
        this.cache = cache;
    }

    @BeforeEach
    void clearState() {
        idempotency.deleteAll();
        payments.deleteAll();
        strings.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
        clearInvocations(payments);
    }

    @Test
    void cacheMissPopulatesPortableJsonAndCacheHitAvoidsRepository() throws Exception {
        UUID id = createdId("M1", "key1");
        String key = cacheKey(id);
        assertThat(strings.hasKey(key)).isFalse(); // creation does not populate before commit
        clearInvocations(payments);

        ResponseEntity<JsonNode> first = get(id);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(payments).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        String json = strings.opsForValue().get(key);
        JsonNode cached = mapper.readTree(json);
        assertThat(cached.path("id").asText()).isEqualTo(id.toString());
        assertThat(cached.path("currency").asText()).isEqualTo("INR");
        assertThat(cached.path("createdAt").isTextual()).isTrue();
        assertThat(cached.has("@class")).isFalse();
        assertThat(strings.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2000L);

        clearInvocations(payments);
        ResponseEntity<JsonNode> second = get(id);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        verify(payments, never()).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void cacheExpiryReturnsToPostgreSqlAndRepopulates() {
        UUID id = createdId("M1", "key1");
        get(id);
        await().atMost(Duration.ofSeconds(5)).until(() -> Boolean.FALSE.equals(strings.hasKey(cacheKey(id))));
        clearInvocations(payments);
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(payments).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        assertThat(strings.hasKey(cacheKey(id))).isTrue();
    }

    @Test
    void evictionAndRedisFlushPreserveDatabasePayments() {
        UUID id = createdId("M1", "key1");
        get(id);
        cache.evict(id);
        assertThat(strings.hasKey(cacheKey(id))).isFalse();
        clearInvocations(payments);
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(payments).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        strings.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("M1", "key1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void malformedCacheJsonFallsBackAndIsOverwritten() {
        UUID id = createdId("M1", "key1");
        strings.opsForValue().set(cacheKey(id), "not-json", Duration.ofSeconds(2));
        clearInvocations(payments);
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(payments).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
        assertThat(strings.opsForValue().get(cacheKey(id))).startsWith("{");
    }

    @Test
    void unknownPaymentIsNotCached() {
        UUID id = UUID.randomUUID();
        assertThat(get(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(strings.hasKey(cacheKey(id))).isFalse();
    }

    @Test
    void redisTimeoutFallsBackForReadsAndReplaysButNewCreationFailsClosed() throws Exception {
        UUID id = createdId("M1", "existing");
        get(id);
        clearInvocations(payments);
        // A real Redis pause deterministically exceeds the client command timeout.
        // UNPAUSE is allowed while clients are paused; no endpoint or mock is substituted.
        assertThat(redis.execInContainer("redis-cli", "CLIENT", "PAUSE", "10000", "ALL").getExitCode()).isZero();
        try {
            ResponseEntity<JsonNode> read = get(id);
            assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(read.getBody().path("id").asText()).isEqualTo(id.toString());
            verify(payments).findByIdAndMerchantId(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyString());
            ResponseEntity<JsonNode> unavailable = post("M1", "new-key");
            assertError(unavailable, HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(post("M1", "existing").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(http.getForEntity("/api/v1/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(payments.count()).isEqualTo(1);
            assertThat(idempotency.count()).isEqualTo(1);
        } finally {
            assertThat(redis.execInContainer("redis-cli", "CLIENT", "UNPAUSE").getExitCode()).isZero();
        }
    }

    @Test
    void threeAttemptsAllowedFourthRateLimitedWithRetryAfter() {
        for (int i = 1; i <= 3; i++) {
            assertThat(post("M1", "key" + i).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<JsonNode> limited = post("M1", "key4");
        assertError(limited, HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().path("message").asText()).isEqualTo("Payment creation rate limit exceeded");
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3L);
        assertThat(payments.count()).isEqualTo(3);
        assertThat(idempotency.count()).isEqualTo(3);
        assertThat(strings.getExpire(rateKey("M1"), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void merchantsHaveIndependentRateLimits() {
        for (int i = 1; i <= 3; i++) {
            post("M1", "key" + i);
        }
        assertThat(post("M1", "key4").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(post("M2", "key1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(strings.opsForValue().get(rateKey("M2"))).isEqualTo("1");
        assertThat(payments.count()).isEqualTo(4);
    }

    @Test
    void committedReplaysAreFreeAndConflictSemanticsSurviveExhaustedQuota() {
        UUID firstId = createdId("M1", "key1");
        post("M1", "key2");
        post("M1", "key3");
        assertThat(strings.opsForValue().get(rateKey("M1"))).isEqualTo("3");
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> replay = post("M1", "key1");
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody().path("id").asText()).isEqualTo(firstId.toString());
        }
        ObjectNode changed = request("M1");
        changed.put("amount", new BigDecimal("500"));
        assertError(post(changed, "key1"), HttpStatus.CONFLICT);
        assertThat(strings.opsForValue().get(rateKey("M1"))).isEqualTo("3");
        assertThat(post("M1", "key4").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(payments.count()).isEqualTo(3);
    }

    @Test
    void windowExpiresWithoutExtendingOnRejectedRequests() {
        for (int i = 1; i <= 3; i++) {
            post("M1", "key" + i);
        }
        long before = strings.getExpire(rateKey("M1"), TimeUnit.MILLISECONDS);
        assertThat(post("M1", "key4").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(strings.getExpire(rateKey("M1"), TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(before);
        await().atMost(Duration.ofSeconds(6)).until(() -> Boolean.FALSE.equals(strings.hasKey(rateKey("M1"))));
        assertThat(post("M1", "key4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(strings.opsForValue().get(rateKey("M1"))).isEqualTo("1");
    }

    @Test
    void limiterRepairsCounterWithoutExpiry() {
        strings.opsForValue().set(rateKey("M1"), "1");
        assertThat(strings.getExpire(rateKey("M1"))).isEqualTo(-1L);
        assertThat(post("M1", "key1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(strings.getExpire(rateKey("M1"), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void concurrentDistinctAttemptsRespectSharedRedisQuota() throws Exception {
        int workers = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                String key = "concurrent-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return post("M1", key);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED).hasSize(3);
            assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS).hasSize(7);
            assertThat(payments.count()).isEqualTo(3);
            assertThat(idempotency.count()).isEqualTo(3);
            assertThat(strings.opsForValue().get(rateKey("M1"))).isEqualTo("10");
            assertThat(strings.getExpire(rateKey("M1"), TimeUnit.MILLISECONDS)).isPositive();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID createdId(String merchant, String key) {
        ResponseEntity<JsonNode> response = post(merchant, key);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(response.getBody().path("id").asText());
    }

    private ResponseEntity<JsonNode> get(UUID id) {
        return http.getForEntity(PATH + "/" + id, JsonNode.class);
    }

    private ResponseEntity<JsonNode> post(String merchant, String key) {
        return post(request(merchant), key);
    }

    private ResponseEntity<JsonNode> post(ObjectNode request, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.postForEntity(PATH, new HttpEntity<>(request, headers), JsonNode.class);
    }

    private ObjectNode request(String merchant) {
        return mapper.createObjectNode().put("merchantId", merchant).put("userId", "U1")
                .put("amount", new BigDecimal("100.00")).put("currency", "INR").put("paymentMethod", "UPI");
    }

    private String cacheKey(UUID id) {
        return "payflow:payment:" + id;
    }

    private String rateKey(String merchant) {
        return "payflow:rate:create-payment:" + merchant;
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
        assertThat(response.getBody().path("error").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(response.getBody().path("path").asText()).isEqualTo(PATH);
        assertThat(response.getBody().path("message").asText()).isNotBlank();
        assertThat(response.getBody().has("trace")).isFalse();
    }
}
