package com.payflow.payment.controller;

import com.payflow.payment.support.KafkaIntegrationSupport;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentIdempotencyIntegrationTest extends KafkaIntegrationSupport {

    private static final String PATH = "/api/v1/payments";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("payflow_idempotency_test")
            .withUsername("payflow_test")
            .withPassword("test_only_password");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private final TestRestTemplate http;
    private final PaymentRepository payments;
    private final PaymentIdempotencyRepository idempotency;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    @Autowired
    PaymentIdempotencyIntegrationTest(TestRestTemplate http, PaymentRepository payments,
            PaymentIdempotencyRepository idempotency, ObjectMapper mapper, JdbcTemplate jdbc) {
        this.http = http;
        this.payments = payments;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clearPayments() {
        idempotency.deleteAll();
        payments.deleteAll();
    }

    @Test
    void firstRequestCreatesPaymentAndIdempotencyRecord() {
        ResponseEntity<JsonNode> response = post(request("M1", "2499.00"), "KEY123");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(response.getBody().path("id").asText());
        assertThat(response.getBody().path("status").asText()).isEqualTo("CREATED");
        assertThat(response.getBody().has("created")).isFalse();
        assertThat(response.getHeaders().getLocation()).hasToString(PATH + "/" + id);
        assertCounts(1);
        var record = idempotency.findByMerchantIdAndIdempotencyKey("M1", "KEY123").orElseThrow();
        assertThat(record.getId()).isNotNull();
        assertThat(record.getPaymentId()).isEqualTo(id);
        assertThat(record.getRequestHash()).matches("[0-9a-f]{64}");
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1', '2') AND success = true",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void identicalReplayReturnsOriginalPaymentAndLocation() {
        ResponseEntity<JsonNode> first = post(request("M1", "2499.00"), "KEY123");
        ResponseEntity<JsonNode> replay = post(request("M1", "2499.00"), "KEY123");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertCounts(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2499", "2499.0", "2499.0000", "2.499E3"})
    void equivalentAmountRepresentationsReplay(String amount) {
        JsonNode first = post(request("M1", "2499.00"), "KEY123").getBody();
        ResponseEntity<JsonNode> replay = post(request("M1", amount), "KEY123");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first);
        assertCounts(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "paymentMethod", "currency", "userId"})
    void differentPayloadReturnsConflict(String field) {
        post(request("M1", "100"), "KEY123");
        ObjectNode changed = request("M1", "100");
        switch (field) {
            case "amount" -> changed.put(field, new BigDecimal("500"));
            case "paymentMethod" -> changed.put(field, "CARD");
            case "currency" -> changed.put(field, "USD");
            case "userId" -> changed.put(field, "another-user");
            default -> throw new AssertionError("Unexpected test field");
        }
        assertConflict(post(changed, "KEY123"));
        assertCounts(1);
    }

    @Test
    void sameKeyIsIndependentAcrossMerchants() {
        ResponseEntity<JsonNode> first = post(request("M1", "100"), "SHARED");
        ResponseEntity<JsonNode> second = post(request("M2", "100"), "SHARED");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().path("id")).isNotEqualTo(first.getBody().path("id"));
        assertCounts(2);
    }

    @Test
    void differentKeysCreateIndependentPaymentsForSameMerchant() {
        ResponseEntity<JsonNode> first = post(request("M1", "100"), "KEY1");
        ResponseEntity<JsonNode> second = post(request("M1", "100"), "KEY2");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().path("id")).isNotEqualTo(first.getBody().path("id"));
        assertCounts(2);
    }

    @Test
    void missingKeyReturnsBadRequest() {
        assertBadRequest(post(request("M1", "100"), null));
        assertCounts(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankKeyReturnsBadRequest(String key) {
        assertBadRequest(post(request("M1", "100"), key));
        assertCounts(0);
    }

    @Test
    void oversizedKeyReturnsBadRequest() {
        assertBadRequest(post(request("M1", "100"), "x".repeat(129)));
        assertCounts(0);
    }

    @Test
    void maximumLengthKeyIsAccepted() {
        assertThat(post(request("M1", "100"), "x".repeat(128)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertCounts(1);
    }

    @Test
    void invalidRequestDoesNotReserveKey() {
        assertBadRequest(post(request("M1", "-100"), "RETRY"));
        assertCounts(0);
        assertThat(post(request("M1", "100"), "RETRY").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertCounts(1);
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOnePayment() throws Exception {
        List<ObjectNode> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(request("M1", "2499.00"));
        }
        List<ResponseEntity<JsonNode>> responses = concurrentRequests(requests, "RACE");
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.OK).hasSize(7);
        assertThat(responses.stream().map(response -> response.getBody().path("id").asText()).distinct().toList())
                .hasSize(1);
        for (ResponseEntity<JsonNode> response : responses) {
            assertThat(response.getBody()).isEqualTo(responses.getFirst().getBody());
        }
        assertCounts(1);
    }

    @Test
    void concurrentDifferentPayloadsReturnOneCreationAndOneConflict() throws Exception {
        List<ResponseEntity<JsonNode>> responses = concurrentRequests(
                List.of(request("M1", "100"), request("M1", "500")), "CONFLICT-RACE");
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CREATED).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT).hasSize(1);
        ResponseEntity<JsonNode> conflict = responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
        assertConflict(conflict);
        assertCounts(1);
    }

    private List<ResponseEntity<JsonNode>> concurrentRequests(List<ObjectNode> requests, String key) throws Exception {
        int workers = requests.size();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        try {
            // SHARE permits reads but blocks inserts. Every request must pass the initial
            // lookup and flush its payment before we release the idempotency INSERTs.
            // This forces actual unique-key collisions rather than hoping scheduling races.
            try (Connection blocker = jdbc.getDataSource().getConnection()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.createStatement()) {
                    statement.execute("LOCK TABLE payment_idempotency IN SHARE MODE");
                }
                try {
                    for (ObjectNode request : requests) {
                        futures.add(executor.submit(() -> {
                            ready.countDown();
                            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                            return post(request, key);
                        }));
                    }
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    start.countDown();
                    await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                            .untilAsserted(() -> assertThat(jdbc.queryForObject("""
                                    SELECT count(*) FROM pg_stat_activity
                                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                                      AND query LIKE 'insert into payment_idempotency%'
                                    """, Integer.class)).isEqualTo(workers));
                } finally {
                    blocker.rollback(); // release the test lock even if an assertion fails
                }
            }
            List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ResponseEntity<JsonNode> post(ObjectNode request, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return http.postForEntity(PATH, new HttpEntity<>(request, headers), JsonNode.class);
    }

    private ObjectNode request(String merchant, String amount) {
        return mapper.createObjectNode().put("merchantId", merchant).put("userId", "USER-456")
                .put("amount", new BigDecimal(amount)).put("currency", "INR").put("paymentMethod", "UPI");
    }

    private void assertCounts(long expected) {
        assertThat(payments.count()).isEqualTo(expected);
        assertThat(idempotency.count()).isEqualTo(expected);
    }

    private void assertConflict(ResponseEntity<JsonNode> response) {
        assertError(response, HttpStatus.CONFLICT);
        assertThat(response.getBody().path("message").asText())
                .isEqualTo("Idempotency key has already been used with a different request");
    }

    private void assertBadRequest(ResponseEntity<JsonNode> response) {
        assertError(response, HttpStatus.BAD_REQUEST);
    }

    private void assertError(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        JsonNode body = response.getBody();
        assertThat(body.path("status").asInt()).isEqualTo(status.value());
        assertThat(body.path("error").asText()).isEqualTo(status.getReasonPhrase());
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("path").asText()).isEqualTo(PATH);
        assertThat(Instant.parse(body.path("timestamp").asText())).isNotNull();
        assertThat(body.has("trace")).isFalse();
    }
}
