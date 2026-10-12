package com.payflow.payment.reconciliation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.*;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.mock.*;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.*;
import com.payflow.payment.security.WebhookSignatureVerifier;
import com.payflow.payment.service.*;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@org.springframework.test.context.TestPropertySource(
        properties = {
            "payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes",
            "payflow.mock-providers.enabled=true"
        })
@org.springframework.context.annotation.Import(
        com.payflow.payment.support.MerchantTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "payflow.outbox.enabled=false",
            "payflow.reconciliation.enabled=false",
            "payflow.reconciliation.initial-delay=10s",
            "payflow.reconciliation.backoff=10s",
            "payflow.reconciliation.max-backoff=40s",
            "payflow.webhooks.a-secret=integration-test-a-secret",
            "payflow.webhooks.b-secret=integration-test-b-secret",
            "payflow.webhooks.mock-delivery-enabled=true",
            "payflow.webhooks.mock-delay-ms=500",
            "payflow.webhooks.mock-poll-interval-ms=3600000",
            "payflow.webhooks.retry-delay-ms=20",
            "payflow.providers.read-timeout=100ms",
            "payflow.providers.connect-timeout=100ms",
            "resilience4j.retry.configs.providerDefault.wait-duration=10ms",
            "resilience4j.circuitbreaker.configs.providerDefault.sliding-window-size=20",
            "resilience4j.circuitbreaker.configs.providerDefault.minimum-number-of-calls=10"
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@MockitoSpyBean(types = {MockProviderA.class, MockProviderB.class, ObjectMapper.class})
class PaymentReconciliationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    private final PaymentRepository payments;
    private final PaymentProviderAttemptRepository providerAttempts;
    private final MockProviderPaymentRepository mockPayments;
    private final OutboxEventRepository outbox;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentOrchestrator orchestrator;
    private final ReconciliationStore store;
    private final PaymentReconciliationService reconciler;
    private final WebhookSignatureVerifier signatures;
    private final MockProviderControl controls;
    private final MockWebhookDispatcher dispatcher;
    private final MockWebhookStore deliveries;
    private final ReconciliationProperties properties;
    private final WebhookProperties webhookProperties;
    private final CircuitBreakerRegistry circuits;
    private final MockProviderA providerA;
    private final MockProviderB providerB;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate cache;
    private final TestRestTemplate http;

    PaymentReconciliationIntegrationTest(
            PaymentRepository payments,
            PaymentProviderAttemptRepository providerAttempts,
            MockProviderPaymentRepository mockPayments,
            OutboxEventRepository outbox,
            ProcessedKafkaEventRepository processed,
            PaymentOrchestrator orchestrator,
            ReconciliationStore store,
            PaymentReconciliationService reconciler,
            WebhookSignatureVerifier signatures,
            MockProviderControl controls,
            MockWebhookDispatcher dispatcher,
            MockWebhookStore deliveries,
            ReconciliationProperties properties,
            WebhookProperties webhookProperties,
            CircuitBreakerRegistry circuits,
            MockProviderA providerA,
            MockProviderB providerB,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            StringRedisTemplate cache,
            TestRestTemplate http) {
        this.payments = payments;
        this.providerAttempts = providerAttempts;
        this.mockPayments = mockPayments;
        this.outbox = outbox;
        this.processed = processed;
        this.orchestrator = orchestrator;
        this.store = store;
        this.reconciler = reconciler;
        this.signatures = signatures;
        this.controls = controls;
        this.dispatcher = dispatcher;
        this.deliveries = deliveries;
        this.properties = properties;
        this.webhookProperties = webhookProperties;
        this.circuits = circuits;
        this.providerA = providerA;
        this.providerB = providerB;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.cache = cache;
        this.http = http;
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM provider_webhook_events");
        jdbc.update("DELETE FROM payment_reconciliation_issues");
        providerAttempts.deleteAll();
        mockPayments.deleteAll();
        outbox.deleteAll();
        processed.deleteAll();
        payments.deleteAll();
        controls.reset();
        properties.setMaxAttempts(10);
        properties.setLease(Duration.ofSeconds(30));
        properties.setBatchSize(50);
        webhookProperties.setMockDelayMs(500);
        webhookProperties.setAUrl("");
        webhookProperties.setBUrl("");
        cache.execute(
                (org.springframework.data.redis.core.RedisCallback<Void>)
                        c -> {
                            c.serverCommands().flushDb();
                            return null;
                        });
        for (String name : List.of("providerA", "providerB", "providerAStatus", "providerBStatus"))
            circuits.circuitBreaker(name).reset();
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return call.callRealMethod();
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA))
                .getPaymentStatus(anyString());
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return call.callRealMethod();
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB))
                .getPaymentStatus(anyString());
    }

    Payment pending(boolean failed) {
        return pending(PaymentProviderType.PROVIDER_A, failed);
    }

    Payment pending(PaymentProviderType provider, boolean failed) {
        controls.configure(
                provider,
                new MockBehavior(
                        failed ? MockScenario.TIMEOUT_DECLINE : MockScenario.TIMEOUT, 0, 350));
        Payment p =
                payments.saveAndFlush(
                        new Payment(
                                "merchant-test",
                                "user-test",
                                new BigDecimal("12.5000"),
                                Currency.getInstance("USD"),
                                provider == PaymentProviderType.PROVIDER_A
                                        ? PaymentMethod.UPI
                                        : PaymentMethod.CARD,
                                PaymentStatus.PROCESSING));
        orchestrator.handle(
                PaymentEvent.forPayment(
                        PaymentEventType.PAYMENT_PROCESSING, PaymentMapper.toResponse(p)));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        return p;
    }

    Payment current(Payment p) {
        return payments.findById(p.getId()).orElseThrow();
    }

    ProviderWebhookRequest body(
            Payment p, String event, String status, BigDecimal amount, String currency) {
        MockProviderPayment row =
                mockPayments.findAll().stream()
                        .filter(m -> m.getPaymentId().equals(p.getId()))
                        .findFirst()
                        .orElseThrow();
        return new ProviderWebhookRequest(
                event,
                row.getProvider() + "-" + row.getId(),
                "payflow:" + p.getId() + ":" + row.getProvider(),
                status,
                amount,
                currency,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    ResponseEntity<String> send(PaymentProviderType provider, byte[] raw, String signature) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) h.set("X-Payflow-Signature", signature);
        return http.postForEntity(
                "/api/v1/webhooks/providers/" + provider.mockPath(),
                new HttpEntity<>(raw, h),
                String.class);
    }

    ResponseEntity<String> send(ProviderWebhookRequest body) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(body);
        return send(
                PaymentProviderType.PROVIDER_A,
                raw,
                signatures.sign(PaymentProviderType.PROVIDER_A, raw));
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    void due(Payment p) {
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET"
                        + " next_reconciliation_at=clock_timestamp()-interval '1 second' WHERE"
                        + " payment_id=?",
                p.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void authenticatedWebhookResolvesUnknownAndEvictsCache(String status) throws Exception {
        Payment p = pending(status.equals("FAILED"));
        cache.opsForValue().set("payflow:payment:" + p.getId(), "stale");
        assertThat(send(body(p, "event-1", status, p.getAmount(), "USD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus().name()).isEqualTo(status);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isFalse();
        assertThat(outbox.findAll().getFirst().getEventType())
                .isEqualTo(status.equals("SUCCESS") ? "PAYMENT_SUCCEEDED" : "PAYMENT_FAILED");
    }

    @Test
    void signaturesVerifyExactRawBytesBeforeParsing() throws Exception {
        Payment p = pending(false);
        byte[] raw = mapper.writeValueAsBytes(body(p, "event-1", "SUCCESS", p.getAmount(), "USD"));
        String signed = signatures.sign(PaymentProviderType.PROVIDER_A, raw);
        assertThat(send(PaymentProviderType.PROVIDER_A, raw, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(PaymentProviderType.PROVIDER_A, raw, "00".repeat(32)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        send(
                                        PaymentProviderType.PROVIDER_A,
                                        raw,
                                        signatures.sign(PaymentProviderType.PROVIDER_B, raw))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        byte[] modified =
                (new String(raw, java.nio.charset.StandardCharsets.UTF_8) + " ")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(send(PaymentProviderType.PROVIDER_A, modified, signed).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        send(PaymentProviderType.PROVIDER_A, "not json".getBytes(), "invalid")
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("provider_webhook_events")).isZero();
        assertThat(outbox.count()).isZero();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(
                        send(
                                        PaymentProviderType.PROVIDER_A,
                                        modified,
                                        signatures.sign(PaymentProviderType.PROVIDER_A, modified))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void invalidSignedPayloadAndUnknownStatusAreRejected() throws Exception {
        Payment p = pending(false);
        assertThat(send(body(p, "x", "REFUNDED", p.getAmount(), "USD")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        byte[] invalid = "not-json".getBytes();
        assertThat(
                        send(
                                        PaymentProviderType.PROVIDER_A,
                                        invalid,
                                        signatures.sign(PaymentProviderType.PROVIDER_A, invalid))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("provider_webhook_events")).isZero();
    }

    @Test
    void duplicateWebhookAndAlteredReplayHaveDifferentOutcomes() throws Exception {
        Payment p = pending(false);
        var body = body(p, "same", "SUCCESS", p.getAmount(), "USD");
        assertThat(send(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(body(p, "same", "FAILED", p.getAmount(), "USD")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void concurrentDuplicateWebhookIsDatabaseDeduplicated() throws Exception {
        Payment p = pending(false);
        byte[] raw = mapper.writeValueAsBytes(body(p, "same", "SUCCESS", p.getAmount(), "USD"));
        String sig = signatures.sign(PaymentProviderType.PROVIDER_A, raw);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Callable<HttpStatusCode>> work = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                work.add(() -> send(PaymentProviderType.PROVIDER_A, raw, sig).getStatusCode());
            for (var result : pool.invokeAll(work))
                assertThat(result.get()).isEqualTo(HttpStatus.OK);
        }
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AMOUNT", "CURRENCY", "TRANSACTION"})
    void mismatchesAreDurableConflicts(String mismatch) throws Exception {
        Payment p = pending(false);
        var original =
                body(
                        p,
                        "mismatch",
                        "SUCCESS",
                        mismatch.equals("AMOUNT") ? new BigDecimal("999") : p.getAmount(),
                        mismatch.equals("CURRENCY") ? "INR" : "USD");
        if (mismatch.equals("TRANSACTION"))
            jdbc.update(
                    "UPDATE payments SET provider_transaction_id='known-original' WHERE id=?",
                    p.getId());
        assertThat(send(original).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void wrongProviderCannotResolveAnotherProvidersPayment() throws Exception {
        Payment p = pending(false);
        var b = body(p, "wrong-provider", "SUCCESS", p.getAmount(), "USD");
        byte[] raw = mapper.writeValueAsBytes(b);
        assertThat(
                        send(
                                        PaymentProviderType.PROVIDER_B,
                                        raw,
                                        signatures.sign(PaymentProviderType.PROVIDER_B, raw))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
        assertThat(outbox.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void finalConfirmationsAreHarmlessAndContradictionsNeverOverwrite(String status)
            throws Exception {
        Payment p = pending(status.equals("FAILED"));
        send(body(p, "first", status, p.getAmount(), "USD"));
        send(body(p, "confirm", status, p.getAmount(), "USD"));
        assertThat(
                        send(body(
                                        p,
                                        "contradiction",
                                        status.equals("SUCCESS") ? "FAILED" : "SUCCESS",
                                        p.getAmount(),
                                        "USD"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus().name()).isEqualTo(status);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
    }

    @Test
    void pollingWaitsForEligibilityThenResolvesUsingContactedProvider() {
        Payment p = pending(false);
        assertThat(store.claim()).isEmpty();
        due(p);
        reconciler.reconcileBatch();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("payment_reconciliation_attempts")).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(1))
                .getPaymentStatus("payflow:" + p.getId() + ":PROVIDER_A");
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .getPaymentStatus(anyString());
    }

    @Test
    void pollingCanConfirmFailure() {
        Payment p = pending(true);
        due(p);
        reconciler.reconcileBatch();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "UNKNOWN", "HTTP_503", "TIMEOUT"})
    void nonFinalAndInfrastructureResultsStayPendingAndBackOff(String mode) {
        Payment p = pending(false);
        controls.configureStatus(
                PaymentProviderType.PROVIDER_A,
                new MockStatusBehavior(
                        MockStatusBehavior.Mode.valueOf(mode), mode.equals("TIMEOUT") ? 350 : 0));
        due(p);
        reconciler.reconcileBatch();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM payment_reconciliation_tasks WHERE"
                                        + " payment_id=?",
                                Integer.class,
                                p.getId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT next_reconciliation_at>clock_timestamp() FROM"
                                        + " payment_reconciliation_tasks WHERE payment_id=?",
                                Boolean.class,
                                p.getId()))
                .isTrue();
        assertThat(store.claim()).isEmpty();
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(mode.equals("HTTP_503") || mode.equals("TIMEOUT") ? 3 : 1))
                .getPaymentStatus(anyString());
    }

    @Test
    void openStatusCircuitLeavesOutcomeUnknown() {
        Payment p = pending(false);
        circuits.circuitBreaker("providerAStatus").transitionToOpenState();
        due(p);
        reconciler.reconcileBatch();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isZero();
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        never())
                .getPaymentStatus(anyString());
    }

    @Test
    void exhaustedPollingEscalatesWithoutFalseFailure() {
        Payment p = pending(false);
        properties.setMaxAttempts(2);
        controls.configureStatus(
                PaymentProviderType.PROVIDER_A,
                new MockStatusBehavior(MockStatusBehavior.Mode.UNKNOWN, 0));
        due(p);
        reconciler.reconcileBatch();
        due(p);
        reconciler.reconcileBatch();
        due(p);
        assertThat(store.claim()).isEmpty();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT escalated FROM payment_reconciliation_tasks WHERE"
                                        + " payment_id=?",
                                Boolean.class,
                                p.getId()))
                .isTrue();
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
    }

    @Test
    void multipleWorkersPerformOneActiveStatusQuery() throws Exception {
        Payment p = pending(false);
        due(p);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            entered.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return call.callRealMethod();
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA))
                .getPaymentStatus(anyString());
        try (var pool = Executors.newFixedThreadPool(6)) {
            var first = pool.submit(() -> reconciler.reconcileBatch());
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                List<Future<?>> others = new ArrayList<>();
                for (int i = 0; i < 5; i++)
                    others.add(pool.submit(() -> reconciler.reconcileBatch()));
                for (var other : others) other.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("payment_reconciliation_attempts")).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(1))
                .getPaymentStatus(anyString());
    }

    @Test
    void expiredClaimIsRecoveredAndOldTokenCannotFinalize() {
        Payment p = pending(false);
        due(p);
        var old = store.claim().orElseThrow();
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET lease_until=clock_timestamp()-interval '1"
                        + " second' WHERE payment_id=?",
                p.getId());
        var recovered = store.claim().orElseThrow();
        var result = providerA.getPaymentStatus(recovered.providerReference());
        assertThat(store.finish(old, result, null)).isFalse();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(store.finish(recovered, result, null)).isTrue();
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("payment_reconciliation_attempts")).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result FROM payment_reconciliation_attempts WHERE id=?",
                                String.class,
                                old.attemptId()))
                .isEqualTo("ABANDONED");
    }

    @Test
    void webhookAndPollRaceCreatesOneTerminalEvent() throws Exception {
        Payment p = pending(false);
        due(p);
        CountDownLatch queried = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            var result = call.callRealMethod();
                            queried.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return result;
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA))
                .getPaymentStatus(anyString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var poll = pool.submit(() -> reconciler.reconcileBatch());
            assertThat(queried.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(send(body(p, "race", "SUCCESS", p.getAmount(), "USD")).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            } finally {
                release.countDown();
            }
            poll.get(10, TimeUnit.SECONDS);
        }
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(count("payment_reconciliation_attempts")).isEqualTo(1);
    }

    @Test
    void webhookStateDedupAndOutboxRollbackTogether() throws Exception {
        Payment p = pending(false);
        var body = body(p, "rollback", "SUCCESS", p.getAmount(), "USD");
        doThrow(new JsonProcessingException("controlled outbox failure") {})
                .when(mapper)
                .writeValueAsString(argThat(value -> value instanceof PaymentEvent));
        assertThat(send(body).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(count("provider_webhook_events")).isZero();
        assertThat(outbox.count()).isZero();
        doCallRealMethod().when(mapper).writeValueAsString(any());
        assertThat(send(body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void signedMockDeliveryRetriesSameEventAfterServerFailure() throws Exception {
        Payment p = pending(false);
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                        + " next_attempt_at=clock_timestamp()-interval '1 second'");
        doThrow(new JsonProcessingException("controlled outbox failure") {})
                .when(mapper)
                .writeValueAsString(argThat(value -> value instanceof PaymentEvent));
        dispatcher.deliver();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(count("provider_webhook_events")).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM mock_provider_webhook_deliveries",
                                Integer.class))
                .isEqualTo(1);
        doCallRealMethod().when(mapper).writeValueAsString(any());
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                        + " next_attempt_at=clock_timestamp()-interval '1 second'");
        dispatcher.deliver();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT provider_event_id FROM provider_webhook_events",
                                String.class))
                .startsWith("mock-");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM mock_provider_webhook_deliveries",
                                String.class))
                .isEqualTo("DELIVERED");
    }

    @Test
    void statusConflictIsAuditedAndDoesNotFinalize() {
        Payment p = pending(false);
        due(p);
        var claim = store.claim().orElseThrow();
        var actual = providerA.getPaymentStatus(claim.providerReference());
        store.finish(
                claim,
                new ProviderStatusResult(
                        actual.provider(),
                        actual.providerReference(),
                        actual.providerTransactionId(),
                        actual.status(),
                        new BigDecimal("999"),
                        actual.currency()),
                null);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void crashedFinalPollingAttemptEscalatesAfterLeaseExpires() {
        Payment p = pending(false);
        properties.setMaxAttempts(1);
        due(p);
        store.claim().orElseThrow();
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET lease_until=clock_timestamp()-interval '1"
                        + " second' WHERE payment_id=?",
                p.getId());
        assertThat(store.claim()).isEmpty();
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void providerBUsesItsOwnSecretAndStatusAdapter(boolean webhook) throws Exception {
        Payment p = pending(PaymentProviderType.PROVIDER_B, false);
        if (webhook) {
            byte[] raw =
                    mapper.writeValueAsBytes(body(p, "b-event", "SUCCESS", p.getAmount(), "USD"));
            assertThat(
                            send(
                                            PaymentProviderType.PROVIDER_B,
                                            raw,
                                            signatures.sign(PaymentProviderType.PROVIDER_B, raw))
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        } else {
            due(p);
            reconciler.reconcileBatch();
            verify(
                            org.springframework.test.util.AopTestUtils
                                    .<MockProviderB>getUltimateTargetObject(providerB),
                            times(1))
                    .getPaymentStatus("payflow:" + p.getId() + ":PROVIDER_B");
            verify(
                            org.springframework.test.util.AopTestUtils
                                    .<MockProviderA>getUltimateTargetObject(providerA),
                            never())
                    .getPaymentStatus(anyString());
        }
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void providerWebhookRetriesAreBoundedAndKeepOnePayload() {
        Payment p = pending(false);
        // A valid signed but unsupported status receives 400 and must not retry.
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                    + " payload=replace(payload,'SUCCESS','UNSUPPORTED'),next_attempt_at=clock_timestamp()-interval"
                    + " '1 second'");
        dispatcher.deliver();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM mock_provider_webhook_deliveries",
                                String.class))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM mock_provider_webhook_deliveries",
                                Integer.class))
                .isEqualTo(1);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
    }

    @Test
    void repeatedWebhookTransportFailureStopsAfterConfiguredMaximum() {
        pending(false);
        webhookProperties.setAUrl("http://127.0.0.1:1/unavailable");
        String original =
                jdbc.queryForObject(
                        "SELECT payload FROM mock_provider_webhook_deliveries", String.class);
        for (int i = 0; i < 3; i++) {
            jdbc.update(
                    "UPDATE mock_provider_webhook_deliveries SET"
                            + " next_attempt_at=clock_timestamp()-interval '1 second'");
            dispatcher.deliver();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM mock_provider_webhook_deliveries",
                                String.class))
                .isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempt_count FROM mock_provider_webhook_deliveries",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT payload FROM mock_provider_webhook_deliveries",
                                String.class))
                .isEqualTo(original);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void pollAtomicFinalizationFailureRecoversAfterLeaseExpiry() throws Exception {
        Payment p = pending(false);
        due(p);
        doThrow(new JsonProcessingException("controlled poll outbox failure") {})
                .when(mapper)
                .writeValueAsString(argThat(value -> value instanceof PaymentEvent));
        assertThatThrownBy(() -> reconciler.reconcileBatch())
                .isInstanceOf(IllegalStateException.class);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT result FROM payment_reconciliation_attempts", String.class))
                .isEqualTo("STARTED");
        doCallRealMethod().when(mapper).writeValueAsString(any());
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET lease_until=clock_timestamp()-interval '1"
                        + " second' WHERE payment_id=?",
                p.getId());
        reconciler.reconcileBatch();
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void lateWebhookCanResolveAfterPollingExhaustion() throws Exception {
        Payment p = pending(false);
        properties.setMaxAttempts(1);
        controls.configureStatus(
                PaymentProviderType.PROVIDER_A,
                new MockStatusBehavior(MockStatusBehavior.Mode.UNKNOWN, 0));
        due(p);
        reconciler.reconcileBatch();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT escalated FROM payment_reconciliation_tasks WHERE"
                                        + " payment_id=?",
                                Boolean.class,
                                p.getId()))
                .isTrue();
        assertThat(send(body(p, "late", "SUCCESS", p.getAmount(), "USD")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT escalated FROM payment_reconciliation_tasks WHERE"
                                        + " payment_id=?",
                                Boolean.class,
                                p.getId()))
                .isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resolution_status FROM payment_reconciliation_issues WHERE"
                                        + " payment_id=?",
                                String.class,
                                p.getId()))
                .isEqualTo("RESOLVED");
    }

    @RepeatedTest(5)
    void webhookRacingWithTaskClaimUsesConsistentLockOrder() throws Exception {
        Payment p = pending(false);
        due(p);
        byte[] raw =
                mapper.writeValueAsBytes(body(p, "claim-race", "SUCCESS", p.getAmount(), "USD"));
        String signature = signatures.sign(PaymentProviderType.PROVIDER_A, raw);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var claimed =
                    pool.submit(
                            () -> {
                                start.await();
                                return store.claim();
                            });
            var webhook =
                    pool.submit(
                            () -> {
                                start.await();
                                return send(PaymentProviderType.PROVIDER_A, raw, signature);
                            });
            start.countDown();
            var optional = claimed.get(10, TimeUnit.SECONDS);
            assertThat(webhook.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            if (optional.isPresent()) {
                var claim = optional.get();
                assertThat(
                                store.finish(
                                        claim,
                                        providerA.getPaymentStatus(claim.providerReference()),
                                        null))
                        .isTrue();
            }
        }
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(1);
    }
}
