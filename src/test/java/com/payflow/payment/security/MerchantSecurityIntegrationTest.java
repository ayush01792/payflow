package com.payflow.payment.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.service.RefundService;
import com.payflow.payment.support.KafkaIntegrationSupport;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payflow.security.api-key-pepper=security-integration-test-pepper-at-least-32-bytes",
            "payflow.mock-providers.enabled=false",
            "payflow.rate-limit.create-limit=3",
            "payflow.rate-limit.create-window-seconds=120",
            "payflow.webhooks.a-secret=independent-webhook-test-secret",
            "payflow.webhooks.b-secret=independent-webhook-test-secret"
        })
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MerchantSecurityIntegrationTest extends KafkaIntegrationSupport {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
    }

    private final TestRestTemplate http;
    private final MerchantCredentialService credentials;
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final RefundService refunds;
    private final StringRedisTemplate strings;
    @MockitoSpyBean ApiKeyUsageAudit audit;

    @Autowired
    MerchantSecurityIntegrationTest(
            TestRestTemplate http,
            MerchantCredentialService credentials,
            JdbcTemplate jdbc,
            PaymentRepository payments,
            RefundService refunds,
            StringRedisTemplate strings) {
        this.http = http;
        this.credentials = credentials;
        this.jdbc = jdbc;
        this.payments = payments;
        this.refunds = refunds;
        this.strings = strings;
    }

    UUID a, b;
    MerchantCredentialService.IssuedKey ka, kb;

    @BeforeEach
    void setup() {
        reset(audit);
        jdbc.execute("TRUNCATE payments CASCADE");
        jdbc.execute("TRUNCATE merchants CASCADE");
        strings.execute(
                c -> {
                    c.serverCommands().flushDb();
                    return null;
                },
                true);
        a = credentials.createMerchant("merchant-a", "Merchant A");
        b = credentials.createMerchant("merchant-b", "Merchant B");
        ka = credentials.createKey(a, "test A", null);
        kb = credentials.createKey(b, "test B", null);
    }

    Map<String, Object> body() {
        return Map.of(
                "userId",
                "USER-456",
                "amount",
                new BigDecimal("2499.00"),
                "currency",
                "INR",
                "paymentMethod",
                "UPI");
    }

    ResponseEntity<JsonNode> post(String key, String idem, Map<String, Object> body) {
        return http.exchange(
                "/api/v1/payments", HttpMethod.POST, entity(key, idem, body), JsonNode.class);
    }

    HttpEntity<Object> entity(String key, String idem, Object body) {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) h.set("X-API-Key", key);
        if (idem != null) h.set("Idempotency-Key", idem);
        return new HttpEntity<>(body, h);
    }

    ResponseEntity<JsonNode> get(String path, String key) {
        return http.exchange(path, HttpMethod.GET, entity(key, null, null), JsonNode.class);
    }

    UUID create(String key, String idem) {
        var res = post(key, idem, body());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(res.getBody().path("id").asText());
    }

    @Test
    void validKeyDerivesIdentityAndUpdatesUsage() {
        UUID id = create(ka.apiKey(), "one");
        assertThat(payments.findById(id).orElseThrow().getMerchantId()).isEqualTo("merchant-a");
        assertThat(credentials.listKeys(a).getFirst().lastUsedAt()).isNotNull();
        assertThat(get("/api/v1/payments/" + id, ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void missingKeyFails() {
        var res = post(null, "one", body());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().path("message").asText())
                .isEqualTo("Invalid or missing credentials");
        assertThat(payments.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "garbage", "payflow_test_x_y", "Bearer garbage", "payflow_live_x_y"})
    void malformedKeyFails(String key) {
        assertThat(post(key, "one", body()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongSecretAndUnknownPrefixFailGenerically() {
        String wrong = ka.apiKey().substring(0, ka.apiKey().length() - 43) + "A".repeat(43);
        var res = post(wrong, "one", body());
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().path("message").asText())
                .isEqualTo("Invalid or missing credentials");
        assertThat(
                        post("payflow_test_" + "B".repeat(22) + "_" + "C".repeat(43), "two", body())
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void revokedKeyFailsImmediatelyIncludingPreviouslyUsedKey() {
        create(ka.apiKey(), "one");
        credentials.revoke(ka.id());
        assertThat(get("/api/v1/payments", ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(credentials.listKeys(a).getFirst().revokedAt()).isNotNull();
    }

    @Test
    void expiredKeyFails() {
        jdbc.update(
                "UPDATE merchant_api_keys SET expires_at=now()-interval '1 second' WHERE id=?",
                ka.id());
        assertThat(post(ka.apiKey(), "one", body()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void suspendedMerchantInvalidatesAllKeys() {
        var second = credentials.createKey(a, "second", null);
        credentials.setStatus(a, MerchantStatus.SUSPENDED);
        assertThat(post(ka.apiKey(), "one", body()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(second.apiKey(), "two", body()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void bodyMerchantIdentityIsRejected() {
        var forged = new HashMap<>(body());
        forged.put("merchantId", "merchant-b");
        assertThat(post(ka.apiKey(), "one", forged).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.count()).isZero();
    }

    @Test
    void crossMerchantGetIsHiddenEvenOnWarmCache() {
        UUID id = create(ka.apiKey(), "one");
        assertThat(get("/api/v1/payments/" + id, ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/payments/" + id, kb.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        strings.delete("payflow:payment:" + id);
        assertThat(get("/api/v1/payments/" + id, kb.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listingsAndPaginationAreMerchantScoped() {
        UUID pa = create(ka.apiKey(), "one"), pb = create(kb.apiKey(), "one");
        var page = get("/api/v1/payments?size=1", ka.apiKey()).getBody();
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        assertThat(page.path("content").get(0).path("id").asText()).isEqualTo(pa.toString());
        assertThat(
                        get("/api/v1/payments", kb.apiKey())
                                .getBody()
                                .path("content")
                                .get(0)
                                .path("id")
                                .asText())
                .isEqualTo(pb.toString());
    }

    @Test
    void identicalIdempotencyKeysStayIndependentAndReplayAcrossRotation() {
        UUID pa = create(ka.apiKey(), "same"), pb = create(kb.apiKey(), "same");
        assertThat(pa).isNotEqualTo(pb);
        var next = credentials.createKey(a, "rotated", null);
        credentials.revoke(ka.id());
        var replay = post(next.apiKey(), "same", body());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("id").asText()).isEqualTo(pa.toString());
    }

    @Test
    void redisRateLimitsIdentityAcrossKeysButSeparatesMerchants() {
        var second = credentials.createKey(a, "second", null);
        create(ka.apiKey(), "one");
        create(second.apiKey(), "two");
        create(ka.apiKey(), "three");
        assertThat(post(second.apiKey(), "four", body()).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(post(kb.apiKey(), "one", body()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post(second.apiKey(), "one", body()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void crossMerchantRefundCreateGetAndReplayAreHidden() {
        UUID id = create(ka.apiKey(), "one");
        jdbc.update(
                "UPDATE payments SET"
                    + " status='SUCCESS',provider='PROVIDER_A',provider_transaction_id='capture-test'"
                    + " WHERE id=?",
                id);
        var body = Map.of("amount", new BigDecimal("100"), "merchantRefundReference", "refund-one");
        String path = "/api/v1/payments/" + id + "/refunds";
        var forbidden =
                http.exchange(
                        path,
                        HttpMethod.POST,
                        entity(kb.apiKey(), "refund-key", body),
                        JsonNode.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds", Integer.class)).isZero();
        var own =
                http.exchange(
                        path,
                        HttpMethod.POST,
                        entity(ka.apiKey(), "refund-key", body),
                        JsonNode.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String refund = own.getBody().path("id").asText();
        assertThat(get("/api/v1/refunds/" + refund, ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/refunds/" + refund, kb.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        http.exchange(
                                        path,
                                        HttpMethod.POST,
                                        entity(kb.apiKey(), "refund-key", body),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rawSecretsNeverPersistOrAppearInMetadataOrLogs(CapturedOutput output) {
        var second = credentials.createKey(a, "second", Instant.now().plusSeconds(3600));
        assertThat(second.apiKey()).isNotEqualTo(ka.apiKey());
        var hashes =
                jdbc.queryForList(
                        "SELECT secret_hash FROM merchant_api_keys WHERE merchant_id=?",
                        String.class,
                        a);
        assertThat(hashes)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allMatch(h -> h.matches("[0-9a-f]{64}"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT row_to_json(k)::text FROM merchant_api_keys k WHERE id=?",
                                String.class,
                                ka.id()))
                .doesNotContain(ka.apiKey(), ka.apiKey().substring(ka.apiKey().length() - 43));
        assertThat(credentials.listKeys(a).toString()).doesNotContain(ka.apiKey(), second.apiKey());
        assertThat(ka.toString()).doesNotContain(ka.apiKey());
        assertThat(output.getAll())
                .doesNotContain(
                        ka.apiKey(),
                        second.apiKey(),
                        "security-integration-test-pepper-at-least-32-bytes");
    }

    @Test
    void usageAuditCoalescesWritesAndRefreshesAnExpiredTimestamp() {
        audit.record(ka.id());
        java.sql.Timestamp first =
                jdbc.queryForObject(
                        "SELECT last_used_at FROM merchant_api_keys WHERE id=?",
                        java.sql.Timestamp.class,
                        ka.id());
        assertThat(first).isNotNull();
        audit.record(ka.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_used_at FROM merchant_api_keys WHERE id=?",
                                java.sql.Timestamp.class,
                                ka.id()))
                .isEqualTo(first);

        jdbc.update(
                "UPDATE merchant_api_keys SET last_used_at=now()-interval '61 seconds' WHERE id=?",
                ka.id());
        audit.record(ka.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_used_at FROM merchant_api_keys WHERE id=?",
                                java.sql.Timestamp.class,
                                ka.id()))
                .isAfterOrEqualTo(first);

        credentials.revoke(ka.id());
        // The coalesced usage write must never become an authentication cache.
        assertThat(get("/api/v1/payments", ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void usageAuditFailureDoesNotFailAuthentication() {
        doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "Test-only audit failure"))
                .when(audit)
                .record(ka.id());
        assertThat(post(ka.apiKey(), "one", body()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void publicHealthStatelessHeadersAndNoBroadCors() {
        var res = get("/api/v1/health", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        var auth = get("/api/v1/payments", ka.apiKey());
        assertThat(auth.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(auth.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(auth.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(auth.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void bearerJwtAndDuplicateHeadersCannotAuthenticateMerchantApi() {
        var h = new HttpHeaders();
        h.setBearerAuth("not-a-jwt");
        assertThat(
                        http.exchange(
                                        "/api/v1/payments",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        h.add("X-API-Key", ka.apiKey());
        h.add("X-API-Key", kb.apiKey());
        assertThat(
                        http.exchange(
                                        "/api/v1/payments",
                                        HttpMethod.GET,
                                        new HttpEntity<>(h),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorProbesArePublicButManagementDoesNotAcceptMerchantKeys() {
        for (String route :
                List.of(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")) {
            var res = get(route, null);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().has("components")).isFalse();
            assertThat(res.getBody().has("details")).isFalse();
            assertThat(res.getBody().path("status").asText()).isEqualTo("UP");
        }
        for (String route :
                List.of(
                        "/actuator/prometheus",
                        "/actuator/info",
                        "/actuator/metrics",
                        "/actuator/env",
                        "/actuator/configprops",
                        "/actuator/heapdump",
                        "/actuator/threaddump")) {
            assertThat(get(route, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(route, ka.apiKey()).getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void mockAndUnimplementedManagementRoutesAreClosed() {
        assertThat(get("/mock/providers/provider-a/behavior", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(ka.apiKey(), "one", body()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get("/api/internal/merchants", ka.apiKey()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void webhookRequiresItsOwnHmacEvenWithValidMerchantKey() {
        var res =
                http.exchange(
                        "/api/v1/webhooks/providers/provider-a",
                        HttpMethod.POST,
                        entity(ka.apiKey(), null, Map.of()),
                        JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().path("message").asText())
                .isNotEqualTo("Invalid or missing credentials");
    }

    @Test
    void validSignedWebhookDoesNotNeedMerchantCredential() throws Exception {
        var payload =
                new com.payflow.payment.dto.ProviderWebhookRequest(
                        "external-event",
                        "external-transaction",
                        "unknown-provider-reference",
                        "SUCCESS",
                        new BigDecimal("100"),
                        "INR",
                        Instant.now());
        byte[] raw =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .writeValueAsBytes(payload);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        "independent-webhook-test-secret"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "HmacSHA256"));
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Payflow-Signature", HexFormat.of().formatHex(mac.doFinal(raw)));
        assertThat(
                        http.postForEntity(
                                        "/api/v1/webhooks/providers/provider-a",
                                        new HttpEntity<>(raw, h),
                                        JsonNode.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherPepperCannotValidateStoredSecrets() {
        var wrong =
                new ApiKeyAuthenticationService(
                        jdbc, new ApiKeyHasher("different-pepper-at-least-32-bytes-long"), audit);
        assertThatThrownBy(() -> wrong.authenticate(ka.apiKey()))
                .isInstanceOf(
                        org.springframework.security.authentication.BadCredentialsException.class)
                .hasMessage("Invalid or missing credentials");
    }

    @Test
    void expiredAndSuspendedAccountsCannotIssueKeys() {
        assertThatThrownBy(() -> credentials.createKey(a, "expired", Instant.now().minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        credentials.setStatus(a, MerchantStatus.SUSPENDED);
        assertThatThrownBy(() -> credentials.createKey(a, "suspended", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrShortPepperFailsClosed() {
        assertThatThrownBy(() -> new ApiKeyHasher("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiKeyHasher("x".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void migrationSafelyPreservesExistingMerchantIdentifiersAndPayments() {
        String schema = "legacy_upgrade";
        var builder =
                org.flywaydb.core.Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .schemas(schema)
                        .defaultSchema(schema)
                        .target("7");
        builder.load().migrate();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " legacy_upgrade.payments(id,merchant_id,user_id,amount,currency,payment_method,status,created_at,updated_at)"
                    + " VALUES (?,'LEGACY-MERCHANT','USER',100,'INR','UPI','SUCCESS',now(),now())",
                id);
        org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM legacy_upgrade.merchants WHERE"
                                        + " external_merchant_id='LEGACY-MERCHANT'",
                                String.class))
                .isEqualTo("SUSPENDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT merchant_id FROM legacy_upgrade.payments WHERE id=?",
                                String.class,
                                id))
                .isEqualTo("LEGACY-MERCHANT");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM legacy_upgrade.merchant_api_keys",
                                Integer.class))
                .isZero();
        jdbc.execute("DROP SCHEMA legacy_upgrade CASCADE");
    }

    @Test
    void bootstrapProfileCreatesOneTimeOwnerOnlyCredentialFile(CapturedOutput output)
            throws Exception {
        try (var context =
                new org.springframework.boot.builder.SpringApplicationBuilder(
                                com.payflow.payment.PaymentApplication.class)
                        .profiles("bootstrap")
                        .run(
                                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                                "--spring.datasource.username=" + postgres.getUsername(),
                                "--spring.datasource.password=" + postgres.getPassword(),
                                "--payflow.security.api-key-pepper=security-integration-test-pepper-at-least-32-bytes",
                                "--payflow.bootstrap.action=create-key",
                                "--payflow.bootstrap.external-id=cli-test-merchant",
                                "--payflow.bootstrap.merchant-name=CLI merchant",
                                "--payflow.bootstrap.key-name=CLI test key")) {
            var match =
                    java.util.regex.Pattern.compile("one-time key file: ([^\\r\\n]+)")
                            .matcher(output.getAll());
            assertThat(match.find()).isTrue();
            var file = java.nio.file.Path.of(match.group(1));
            try {
                assertThat(java.nio.file.Files.getPosixFilePermissions(file))
                        .isEqualTo(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------"));
                String raw = java.nio.file.Files.readString(file).strip();
                assertThat(get("/api/v1/payments", raw).getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(output.getAll()).doesNotContain(raw);
            } finally {
                java.nio.file.Files.deleteIfExists(file);
            }
        }
    }
}
