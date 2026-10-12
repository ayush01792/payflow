package com.payflow.payment.refund;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.*;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.mock.*;
import com.payflow.payment.outbox.OutboxPublisher;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.*;
import com.payflow.payment.security.WebhookSignatureVerifier;
import com.payflow.payment.service.*;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
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
            "payflow.webhooks.a-secret=refund-test-a-secret",
            "payflow.webhooks.b-secret=refund-test-b-secret",
            "payflow.webhooks.mock-delivery-enabled=true",
            "payflow.webhooks.mock-delay-ms=500",
            "payflow.webhooks.mock-poll-interval-ms=3600000",
            "payflow.providers.read-timeout=100ms",
            "payflow.providers.connect-timeout=100ms",
            "resilience4j.retry.configs.providerDefault.wait-duration=10ms",
            "resilience4j.circuitbreaker.configs.providerDefault.sliding-window-size=20",
            "resilience4j.circuitbreaker.configs.providerDefault.minimum-number-of-calls=10"
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@MockitoSpyBean(
        types = {
            MockProviderA.class,
            MockProviderB.class,
            ObjectMapper.class,
            RefundOrchestrator.class
        })
class RefundIntegrationTest {
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
    private final PaymentProviderAttemptRepository paymentAttempts;
    private final MockProviderPaymentRepository mockPayments;
    private final OutboxEventRepository outbox;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentOrchestrator paymentOrchestrator;
    private final RefundOrchestrator orchestrator;
    private final RefundLifecycleService lifecycle;
    private final RefundService service;
    private final RefundStore store;
    private final RefundReconciliationStore reconciliation;
    private final RefundReconciliationService poller;
    private final PaymentCompensationService compensations;
    private final WebhookSignatureVerifier signatures;
    private final MockProviderControl controls;
    private final MockWebhookDispatcher dispatcher;
    private final ReconciliationProperties properties;
    private final WebhookProperties webhooks;
    private final CircuitBreakerRegistry circuits;
    private final MockProviderA providerA;
    private final MockProviderB providerB;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate cache;
    private final TestRestTemplate http;
    private final OutboxPublisher publisher;
    private final KafkaListenerEndpointRegistry listeners;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    RefundIntegrationTest(
            PaymentRepository payments,
            PaymentProviderAttemptRepository paymentAttempts,
            MockProviderPaymentRepository mockPayments,
            OutboxEventRepository outbox,
            ProcessedKafkaEventRepository processed,
            PaymentOrchestrator paymentOrchestrator,
            RefundOrchestrator orchestrator,
            RefundLifecycleService lifecycle,
            RefundService service,
            RefundStore store,
            RefundReconciliationStore reconciliation,
            RefundReconciliationService poller,
            PaymentCompensationService compensations,
            WebhookSignatureVerifier signatures,
            MockProviderControl controls,
            MockWebhookDispatcher dispatcher,
            ReconciliationProperties properties,
            WebhookProperties webhooks,
            CircuitBreakerRegistry circuits,
            MockProviderA providerA,
            MockProviderB providerB,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            StringRedisTemplate cache,
            TestRestTemplate http,
            OutboxPublisher publisher,
            KafkaListenerEndpointRegistry listeners,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.payments = payments;
        this.paymentAttempts = paymentAttempts;
        this.mockPayments = mockPayments;
        this.outbox = outbox;
        this.processed = processed;
        this.paymentOrchestrator = paymentOrchestrator;
        this.orchestrator = orchestrator;
        this.lifecycle = lifecycle;
        this.service = service;
        this.store = store;
        this.reconciliation = reconciliation;
        this.poller = poller;
        this.compensations = compensations;
        this.signatures = signatures;
        this.controls = controls;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.webhooks = webhooks;
        this.circuits = circuits;
        this.providerA = providerA;
        this.providerB = providerB;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.cache = cache;
        this.http = http;
        this.publisher = publisher;
        this.listeners = listeners;
        this.kafkaTemplate = kafkaTemplate;
    }

    @BeforeEach
    void clean() {
        listeners.getListenerContainer("refundLifecycleListener").stop();
        jdbc.update("DELETE FROM provider_webhook_events");
        jdbc.update("DELETE FROM payment_reconciliation_issues");
        jdbc.update("DELETE FROM mock_provider_webhook_deliveries");
        jdbc.update("DELETE FROM mock_provider_refunds");
        jdbc.update("DELETE FROM processed_refund_events");
        jdbc.update("DELETE FROM refunds");
        paymentAttempts.deleteAll();
        mockPayments.deleteAll();
        outbox.deleteAll();
        processed.deleteAll();
        payments.deleteAll();
        controls.reset();
        webhooks.setMockDeliveryEnabled(false);
        properties.setMaxAttempts(10);
        properties.setLease(Duration.ofSeconds(30));
        cache.execute(
                (org.springframework.data.redis.core.RedisCallback<Void>)
                        c -> {
                            c.serverCommands().flushDb();
                            return null;
                        });
        for (String name :
                List.of(
                        "providerA",
                        "providerB",
                        "providerARefund",
                        "providerBRefund",
                        "providerARefundStatus",
                        "providerBRefundStatus")) circuits.circuitBreaker(name).reset();
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
                .refundPayment(any());
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
                .refundPayment(any());
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
                .getRefundStatus(anyString());
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
                .getRefundStatus(anyString());
    }

    @AfterEach
    void stop() {
        listeners.getListenerContainer("refundLifecycleListener").stop();
    }

    Payment captured(String amount) {
        return captured(amount, PaymentProviderType.PROVIDER_A);
    }

    Payment captured(String amount, PaymentProviderType provider) {
        Payment p =
                payments.saveAndFlush(
                        new Payment(
                                "merchant-" + UUID.randomUUID(),
                                "user",
                                new BigDecimal(amount),
                                Currency.getInstance("USD"),
                                provider == PaymentProviderType.PROVIDER_A
                                        ? PaymentMethod.UPI
                                        : PaymentMethod.CARD,
                                PaymentStatus.PROCESSING));
        paymentOrchestrator.handle(
                PaymentEvent.forPayment(
                        PaymentEventType.PAYMENT_PROCESSING, PaymentMapper.toResponse(p)));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        outbox.deleteAll();
        return current(p);
    }

    Payment current(Payment p) {
        return payments.findById(p.getId()).orElseThrow();
    }

    RefundResponse create(Payment p, String amount) {
        return service.create(
                p.getId(),
                new CreateRefundRequest(new BigDecimal(amount), "reference-" + UUID.randomUUID()),
                UUID.randomUUID().toString());
    }

    RefundResponse process(RefundResponse refund) {
        lifecycle.handle(event(refund, RefundEventType.REFUND_CREATED));
        var processing = service.get(refund.id());
        orchestrator.handle(event(processing, RefundEventType.REFUND_PROCESSING));
        return service.get(refund.id());
    }

    RefundEvent event(RefundResponse refund, RefundEventType type) {
        String merchant =
                jdbc.queryForObject(
                        "SELECT merchant_id FROM payments WHERE id=?",
                        String.class,
                        refund.paymentId());
        return RefundEvent.forRefund(type, refund, merchant);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    long attempts(UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refund_provider_attempts WHERE refund_id=?", Long.class, id);
    }

    void due(RefundResponse refund) {
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET"
                        + " next_reconciliation_at=clock_timestamp()-interval '1 second' WHERE"
                        + " refund_id=?",
                refund.id());
    }

    RefundResponse pending(Payment p, String amount, boolean failure) {
        controls.configureRefund(
                p.getProvider(),
                new MockBehavior(
                        failure ? MockScenario.TIMEOUT_DECLINE : MockScenario.TIMEOUT, 0, 350));
        var result = process(create(p, amount));
        assertThat(result.status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        return result;
    }

    ResponseEntity<String> webhook(
            RefundResponse refund,
            String eventId,
            String status,
            BigDecimal amount,
            String currency)
            throws Exception {
        UUID ledger =
                jdbc.queryForObject(
                        "SELECT id FROM mock_provider_refunds WHERE refund_id=?",
                        UUID.class,
                        refund.id());
        var body =
                new ProviderWebhookRequest(
                        eventId,
                        refund.provider() + "-REFUND-" + ledger,
                        RefundStore.key(refund.id(), refund.provider()),
                        status,
                        amount,
                        currency,
                        Instant.parse("2026-01-01T00:00:00Z"));
        byte[] raw = mapper.writeValueAsBytes(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Payflow-Signature", signatures.sign(refund.provider(), raw));
        return http.postForEntity(
                "/api/v1/webhooks/providers/" + refund.provider().mockPath(),
                new HttpEntity<>(raw, headers),
                String.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2500", "1000"})
    void fullOrPartialRefundUpdatesPaymentAndEvictsCache(String amount) {
        Payment p = captured("2500");
        http.getForEntity("/api/v1/payments/" + p.getId(), PaymentResponse.class);
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isTrue();
        var result = process(create(p, amount));
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(current(p).getStatus())
                .isEqualTo(
                        amount.equals("2500")
                                ? PaymentStatus.REFUNDED
                                : PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isFalse();
        assertThat(outbox.count()).isEqualTo(3);
        assertThat(attempts(result.id())).isEqualTo(1);
    }

    @Test
    void multiplePartialRefundsReachExactFullAmount() {
        Payment p = captured("2500");
        for (String amount : List.of("1000", "500", "1000"))
            assertThat(process(create(p, amount)).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(amount) FROM refunds WHERE payment_id=?",
                                BigDecimal.class,
                                p.getId()))
                .isEqualByComparingTo("2500");
    }

    @Test
    void reservedAndSuccessfulAmountsPreventOverRefund() {
        Payment p = captured("2500");
        create(p, "2000");
        assertThatThrownBy(() -> create(p, "1000"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCannotOverReserve() throws Exception {
        Payment p = captured("1000");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    try {
                                        create(p, "700");
                                        return true;
                                    } catch (
                                            com.payflow.payment.exception.RefundConflictException
                                                    conflict) {
                                        return false;
                                    }
                                }));
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT sum(amount) FROM refunds WHERE payment_id=?",
                                BigDecimal.class,
                                p.getId()))
                .isEqualByComparingTo("700");
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void creationIdempotencyReplaysOriginalSnapshotAndRejectsChanges() {
        Payment p = captured("1000");
        var req = new CreateRefundRequest(new BigDecimal("500"), "ORDER-refund");
        var first = service.create(p.getId(), req, "same");
        process(first);
        var replay =
                service.create(
                        p.getId(),
                        new CreateRefundRequest(new BigDecimal("500.0000"), "ORDER-refund"),
                        "same");
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        p.getId(),
                                        new CreateRefundRequest(
                                                new BigDecimal("700"), "ORDER-refund"),
                                        "same"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        p.getId(),
                                        new CreateRefundRequest(new BigDecimal("500"), "changed"),
                                        "same"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @Test
    void concurrentIdempotencyCreatesOneRefund() throws Exception {
        Payment p = captured("1000");
        var request = new CreateRefundRequest(new BigDecimal("500"), "one-reference");
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Callable<UUID>> work = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                work.add(() -> service.create(p.getId(), request, "same").id());
            Set<UUID> ids = new HashSet<>();
            for (var result : pool.invokeAll(work)) ids.add(result.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void merchantReferenceIsUniqueWithinPayment() {
        Payment p = captured("1000");
        var request = new CreateRefundRequest(new BigDecimal("500"), "same-reference");
        service.create(p.getId(), request, "key1");
        assertThatThrownBy(() -> service.create(p.getId(), request, "key2"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("refunds")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CREATED", "PROCESSING", "FAILED", "PENDING_CONFIRMATION", "REFUNDED"})
    void uncertainOrNonRefundablePaymentsAreRejected(PaymentStatus status) {
        Payment p =
                payments.saveAndFlush(
                        new Payment(
                                "merchant",
                                "user",
                                new BigDecimal("1000"),
                                Currency.getInstance("USD"),
                                PaymentMethod.UPI,
                                status));
        assertThatThrownBy(() -> create(p, "1"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("refunds")).isZero();
    }

    @Test
    void refundApiReturnsAcceptedAndStandardErrors() {
        Payment p = captured("1000");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "api-key");
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response =
                http.postForEntity(
                        "/api/v1/payments/" + p.getId() + "/refunds",
                        new HttpEntity<>(
                                new CreateRefundRequest(new BigDecimal("100"), "api-reference"),
                                headers),
                        RefundResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo(RefundStatus.CREATED);
        assertThat(
                        http.getForEntity(
                                        "/api/v1/refunds/" + response.getBody().id(),
                                        RefundResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        http.getForEntity("/api/v1/refunds/" + UUID.randomUUID(), String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/payments/" + p.getId() + "/refunds",
                                        new HttpEntity<>(
                                                Map.of(
                                                        "amount",
                                                        0,
                                                        "merchantRefundReference",
                                                        "bad"),
                                                headers),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/payments/" + UUID.randomUUID() + "/refunds",
                                        new HttpEntity<>(
                                                Map.of(
                                                        "amount",
                                                        1,
                                                        "merchantRefundReference",
                                                        "valid"),
                                                headers),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/payments/" + p.getId() + "/refunds",
                                        Map.of(
                                                "amount",
                                                1,
                                                "merchantRefundReference",
                                                "missing-header"),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void declineHasNoRetryOrFailoverAndReleasesReservation() {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.DECLINE, 0, 0));
        var failed = process(create(p, "1000"));
        assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempts(failed.id())).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .refundPayment(any());
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(create(p, "1000").status()).isEqualTo(RefundStatus.CREATED);
    }

    @Test
    void transientFailuresRetryWithStableKey() {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.HTTP_500, 2, 0));
        var result = process(create(p, "1000"));
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(attempts(result.id())).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(DISTINCT provider_idempotency_key) FROM"
                                        + " refund_provider_attempts",
                                Long.class))
                .isEqualTo(1);
        assertThat(count("mock_provider_refunds")).isEqualTo(1);
    }

    @Test
    void knownUnavailabilityFailsWithoutCrossProviderFallback() {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.HTTP_500, 0, 0));
        var result = process(create(p, "1000"));
        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempts(result.id())).isEqualTo(3);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .refundPayment(any());
        assertThat(create(p, "1000").status()).isEqualTo(RefundStatus.CREATED);
    }

    @Test
    void permanentRejectionIsNotRetried() {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.HTTP_400, 0, 0));
        var result = process(create(p, "1000"));
        assertThat(result.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempts(result.id())).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .refundPayment(any());
    }

    @Test
    void slowSuccessReplaysOriginalProviderRefund() {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A,
                new MockBehavior(MockScenario.SLOW_SUCCESS, 0, 350));
        var result = process(create(p, "1000"));
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(attempts(result.id())).isEqualTo(2);
        assertThat(count("mock_provider_refunds")).isEqualTo(1);
    }

    @Test
    void timeoutKeepsAmountReservedAndEmitsUncertainty() {
        Payment p = captured("1000");
        var result = pending(p, "1000", false);
        assertThat(attempts(result.id())).isEqualTo(3);
        assertThatThrownBy(() -> create(p, "1"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(outbox.findAll())
                .anyMatch(row -> row.getEventType().equals("REFUND_PENDING_CONFIRMATION"))
                .noneMatch(row -> row.getEventType().equals("REFUND_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refundWebhookResolvesSuccessOrFailure(boolean success) throws Exception {
        Payment p = captured("1000");
        var result = pending(p, "1000", !success);
        assertThat(
                        webhook(
                                        result,
                                        "refund-event",
                                        success ? "REFUND_SUCCESS" : "REFUND_FAILED",
                                        result.amount(),
                                        result.currency())
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(service.get(result.id()).status())
                .isEqualTo(success ? RefundStatus.SUCCESS : RefundStatus.FAILED);
        assertThat(current(p).getStatus())
                .isEqualTo(success ? PaymentStatus.REFUNDED : PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void refundWebhookUsesSameDurableInboxAndDetectsAlteredReplay() throws Exception {
        Payment p = captured("1000");
        var result = pending(p, "1000", false);
        assertThat(
                        webhook(result, "same", "REFUND_SUCCESS", result.amount(), "USD")
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(
                        webhook(result, "same", "REFUND_SUCCESS", result.amount(), "USD")
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(webhook(result, "same", "REFUND_FAILED", result.amount(), "USD").getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void amountMismatchAndTerminalContradictionAreAudited() throws Exception {
        Payment p = captured("1000");
        var result = pending(p, "1000", false);
        assertThat(
                        webhook(result, "amount", "REFUND_SUCCESS", new BigDecimal("999"), "USD")
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(service.get(result.id()).status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        webhook(result, "confirm", "REFUND_SUCCESS", result.amount(), "USD");
        webhook(result, "contradiction", "REFUND_FAILED", result.amount(), "USD");
        assertThat(service.get(result.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(2);
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void pollingResolvesLostRefundWebhook() {
        Payment p = captured("1000");
        var result = pending(p, "1000", false);
        assertThat(reconciliation.claim()).isEmpty();
        due(result);
        poller.reconcileBatch();
        assertThat(service.get(result.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(outbox.count()).isEqualTo(4);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .getRefundStatus(anyString());
    }

    @Test
    void pollingInfrastructureFailureNeverReleasesReservation() {
        Payment p = captured("1000");
        var result = pending(p, "1000", false);
        controls.configureRefundStatus(
                PaymentProviderType.PROVIDER_A,
                new MockStatusBehavior(MockStatusBehavior.Mode.HTTP_503, 0));
        due(result);
        poller.reconcileBatch();
        assertThat(service.get(result.id()).status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isEqualTo(3);
        assertThatThrownBy(() -> create(p, "1"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(3))
                .getRefundStatus(anyString());
    }

    @Test
    void refundWebhookAndPollingRaceCreatesOneTerminalEvent() throws Exception {
        Payment p = captured("1000");
        var refund = pending(p, "1000", false);
        due(refund);
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
                .getRefundStatus(anyString());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var polling = pool.submit(() -> poller.reconcileBatch());
            assertThat(queried.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(
                                webhook(refund, "race", "REFUND_SUCCESS", refund.amount(), "USD")
                                        .getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            } finally {
                release.countDown();
            }
            polling.get(10, TimeUnit.SECONDS);
        }
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(
                        outbox.findAll().stream()
                                .filter(row -> row.getEventType().equals("REFUND_SUCCEEDED")))
                .hasSize(1);
    }

    @Test
    void compensationCommandCreatesOneRefundAndCompletes() {
        Payment p = captured("1000");
        UUID command = UUID.randomUUID();
        var first =
                compensations.compensate(command, p.getId(), "Reservation failed after capture");
        var replay =
                compensations.compensate(command, p.getId(), "Reservation failed after capture");
        assertThat(replay.refundId()).isEqualTo(first.refundId());
        assertThat(count("payment_compensations")).isEqualTo(1);
        assertThat(count("refunds")).isEqualTo(1);
        process(service.get(first.refundId()));
        assertThat(compensations.get(command).status()).isEqualTo("COMPLETED");
    }

    @Test
    void compensationUnknownAndFailedStatesReflectRefund() {
        Payment p = captured("1000");
        UUID command = UUID.randomUUID();
        var compensation = compensations.compensate(command, p.getId(), "Fulfillment failed");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A,
                new MockBehavior(MockScenario.TIMEOUT_DECLINE, 0, 350));
        var refund = process(service.get(compensation.refundId()));
        assertThat(compensations.get(command).status()).isEqualTo("PENDING_CONFIRMATION");
        due(refund);
        poller.reconcileBatch();
        assertThat(compensations.get(command).status()).isEqualTo("FAILED");
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void compensationUsesAvailableBalanceAndRejectsChangedCommand() {
        Payment p = captured("1000");
        process(create(p, "400"));
        UUID id = UUID.randomUUID();
        var comp = compensations.compensate(id, p.getId(), "Failure");
        assertThat(service.get(comp.refundId()).amount()).isEqualByComparingTo("600");
        assertThatThrownBy(() -> compensations.compensate(id, p.getId(), "Changed reason"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("refunds")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void finalizationRollbackRecoversKnownResultBeforeOpenCircuit(boolean success)
            throws Exception {
        Payment p = captured("1000");
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A,
                new MockBehavior(success ? MockScenario.SUCCESS : MockScenario.DECLINE, 0, 0));
        var refund = create(p, "1000");
        lifecycle.handle(event(refund, RefundEventType.REFUND_CREATED));
        var source = event(service.get(refund.id()), RefundEventType.REFUND_PROCESSING);
        doThrow(new JsonProcessingException("controlled refund outbox failure") {})
                .when(mapper)
                .writeValueAsString(
                        argThat(
                                value ->
                                        value instanceof RefundEvent e
                                                && (e.eventType()
                                                                == RefundEventType.REFUND_SUCCEEDED
                                                        || e.eventType()
                                                                == RefundEventType.REFUND_FAILED)));
        assertThatThrownBy(() -> orchestrator.handle(source))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(outbox.count()).isEqualTo(2);
        doCallRealMethod().when(mapper).writeValueAsString(any());
        circuits.circuitBreaker("providerARefund").transitionToOpenState();
        orchestrator.handle(source);
        assertThat(service.get(refund.id()).status())
                .isEqualTo(success ? RefundStatus.SUCCESS : RefundStatus.FAILED);
        assertThat(attempts(refund.id())).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(1))
                .refundPayment(any());
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .refundPayment(any());
        assertThat(outbox.count()).isEqualTo(3);
    }

    @Test
    void duplicateProcessingNeverRepeatsProviderOrTerminalEvent() {
        Payment p = captured("1000");
        var refund = create(p, "1000");
        lifecycle.handle(event(refund, RefundEventType.REFUND_CREATED));
        var source = event(service.get(refund.id()), RefundEventType.REFUND_PROCESSING);
        orchestrator.handle(source);
        orchestrator.handle(source);
        orchestrator.handle(source);
        assertThat(attempts(refund.id())).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(3);
    }

    @Test
    void providerRefundKeyIsDurableAcrossConcurrentHttpCalls() throws Exception {
        Payment p = captured("1000");
        var refund = create(p, "1000");
        var request =
                new ProviderRefundRequest(
                        refund.id(),
                        p.getId(),
                        p.getProviderTransactionId(),
                        refund.amount(),
                        refund.currency(),
                        RefundStore.key(refund.id(), refund.provider()));
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Callable<String>> work = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                work.add(() -> providerA.refundPayment(request).providerRefundId());
            Set<String> ids = new HashSet<>();
            for (var future : pool.invokeAll(work)) ids.add(future.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("mock_provider_refunds")).isEqualTo(1);
    }

    @Test
    void originalProviderBReceivesRefundAndStatusPolling() {
        Payment p = captured("1000", PaymentProviderType.PROVIDER_B);
        var refund = pending(p, "1000", false);
        due(refund);
        poller.reconcileBatch();
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        never())
                .refundPayment(any());
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        never())
                .getRefundStatus(anyString());
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        times(3))
                .refundPayment(any());
    }

    @Test
    void refundEventsTravelThroughRealKafkaOutbox() throws Exception {
        Payment p = captured("1000");
        var refund = create(p, "1000");
        var listener = listeners.getListenerContainer("refundLifecycleListener");
        listener.start();
        try {
            await().atMost(Duration.ofSeconds(20))
                    .until(
                            () ->
                                    listener.getAssignedPartitions() != null
                                            && listener.getAssignedPartitions().size() == 3);
            publisher.publishPending();
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> service.get(refund.id()).status() == RefundStatus.PROCESSING);
            publisher.publishPending();
            await().atMost(Duration.ofSeconds(15))
                    .until(() -> service.get(refund.id()).status() == RefundStatus.SUCCESS);
            publisher.publishPending();
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () ->
                                    assertThat(outbox.findAll())
                                            .hasSize(3)
                                            .allMatch(
                                                    row ->
                                                            row.getStatus()
                                                                    == OutboxStatus.PUBLISHED));
            assertThat(attempts(refund.id())).isEqualTo(1);
            var stored =
                    outbox.findAll().stream()
                            .filter(row -> row.getEventType().equals("REFUND_PROCESSING"))
                            .findFirst()
                            .orElseThrow();
            kafkaTemplate
                    .send(
                            RefundEventTopics.EVENTS,
                            refund.id().toString(),
                            stored.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .get(10, TimeUnit.SECONDS);
            kafkaTemplate
                    .send(
                            RefundEventTopics.EVENTS,
                            refund.id().toString(),
                            stored.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(
                            () ->
                                    verify(
                                                    org.springframework.test.util.AopTestUtils
                                                            .<RefundOrchestrator>
                                                                    getUltimateTargetObject(
                                                                            orchestrator),
                                                    atLeast(3))
                                            .handle(any()));
            assertThat(attempts(refund.id())).isEqualTo(1);
            assertThat(outbox.count()).isEqualTo(3);
        } finally {
            listener.stop();
        }
    }

    @Test
    void mockSignedRefundWebhookQueueRecoversTimeout() {
        Payment p = captured("1000");
        webhooks.setMockDeliveryEnabled(true);
        var refund = pending(p, "1000", false);
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                        + " next_attempt_at=clock_timestamp()-interval '1 second'");
        dispatcher.deliver();
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(count("provider_webhook_events")).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT provider_event_id FROM provider_webhook_events",
                                String.class))
                .startsWith("mock-refund-");
    }

    @Test
    void failedCreationRollsBackRefundIdempotencyAndOutbox() throws Exception {
        Payment p = captured("1000");
        doThrow(new JsonProcessingException("controlled creation failure") {})
                .when(mapper)
                .writeValueAsString(
                        argThat(
                                value ->
                                        value instanceof RefundEvent e
                                                && e.eventType()
                                                        == RefundEventType.REFUND_CREATED));
        assertThatThrownBy(() -> create(p, "1000")).isInstanceOf(IllegalStateException.class);
        assertThat(count("refunds")).isZero();
        assertThat(count("refund_idempotency")).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void compensationAndRefundCreationRollbackTogether() throws Exception {
        Payment p = captured("1000");
        doThrow(new JsonProcessingException("controlled compensation failure") {})
                .when(mapper)
                .writeValueAsString(argThat(value -> value instanceof RefundEvent));
        assertThatThrownBy(
                        () ->
                                compensations.compensate(
                                        UUID.randomUUID(), p.getId(), "Reservation failed"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count("refunds")).isZero();
        assertThat(count("payment_compensations")).isZero();
        assertThat(count("refund_idempotency")).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void concurrentCompensationCommandCreatesOneRefund() throws Exception {
        Payment p = captured("1000");
        UUID command = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Callable<UUID>> work = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                work.add(
                        () ->
                                compensations
                                        .compensate(
                                                command, p.getId(), "Downstream fulfillment failed")
                                        .refundId());
            Set<UUID> ids = new HashSet<>();
            for (var future : pool.invokeAll(work)) ids.add(future.get());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("refunds")).isEqualTo(1);
        assertThat(count("payment_compensations")).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void activeRefundLeasePreventsConcurrentExecution() throws Exception {
        Payment p = captured("1000");
        var refund = create(p, "1000");
        lifecycle.handle(event(refund, RefundEventType.REFUND_CREATED));
        var source = event(service.get(refund.id()), RefundEventType.REFUND_PROCESSING);
        CountDownLatch called = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            called.countDown();
                            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            return call.callRealMethod();
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA))
                .refundPayment(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> orchestrator.handle(source));
            assertThat(called.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> orchestrator.handle(source))
                        .isInstanceOf(ProviderWorkInProgressException.class);
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertThat(attempts(refund.id())).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(3);
    }

    @Test
    void expiredOrchestrationClaimIsFencedAndUsesSameProviderKey() {
        Payment p = captured("1000");
        var refund = create(p, "1000");
        lifecycle.handle(event(refund, RefundEventType.REFUND_CREATED));
        var source = event(service.get(refund.id()), RefundEventType.REFUND_PROCESSING);
        var old = store.claim(source).orElseThrow();
        store.startAttempt(old);
        jdbc.update(
                "UPDATE refunds SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",
                refund.id());
        var recovered = store.claim(source).orElseThrow();
        assertThatThrownBy(
                        () ->
                                store.finish(
                                        old,
                                        null,
                                        new ProviderTechnicalException(
                                                "UNAVAILABLE", false, false)))
                .isInstanceOf(ProviderWorkInProgressException.class);
        var attempt = store.startAttempt(recovered);
        var result = providerA.refundPayment(recovered.request());
        store.completeAttempt(attempt, result);
        store.finish(recovered, result, null);
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(count("mock_provider_refunds")).isEqualTo(1);
    }

    @Test
    void reconciliationCrashRecoveryRejectsStaleWorker() {
        Payment p = captured("1000");
        var refund = pending(p, "1000", false);
        due(refund);
        var old = reconciliation.claim().orElseThrow();
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET lease_until=clock_timestamp()-interval '1"
                        + " second' WHERE refund_id=?",
                refund.id());
        var recovered = reconciliation.claim().orElseThrow();
        var report = providerA.getRefundStatus(recovered.reference());
        assertThat(reconciliation.finish(old, report, null)).isFalse();
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        assertThat(reconciliation.finish(recovered, report, null)).isTrue();
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void reconciliationExhaustionKeepsReservationAndLateWebhookCanResolve() throws Exception {
        Payment p = captured("1000");
        var refund = pending(p, "1000", false);
        properties.setMaxAttempts(1);
        controls.configureRefundStatus(
                PaymentProviderType.PROVIDER_A,
                new MockStatusBehavior(MockStatusBehavior.Mode.UNKNOWN, 0));
        due(refund);
        poller.reconcileBatch();
        assertThat(reconciliation.claim()).isEmpty();
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        assertThatThrownBy(() -> create(p, "1"))
                .isInstanceOf(com.payflow.payment.exception.RefundConflictException.class);
        assertThat(count("payment_reconciliation_issues")).isEqualTo(1);
        assertThat(
                        webhook(
                                        refund,
                                        "late",
                                        "REFUND_SUCCESS",
                                        refund.amount(),
                                        refund.currency())
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT resolution_status FROM payment_reconciliation_issues",
                                String.class))
                .isEqualTo("RESOLVED");
    }

    @Test
    void signedChargeConfirmationDoesNotReverseRefundedPaymentSummary() throws Exception {
        Payment p = captured("1000");
        process(create(p, "400"));
        var body =
                new ProviderWebhookRequest(
                        "charge-confirmation",
                        p.getProviderTransactionId(),
                        "payflow:" + p.getId() + ":" + p.getProvider(),
                        "SUCCESS",
                        p.getAmount(),
                        "USD",
                        Instant.now());
        byte[] raw = mapper.writeValueAsBytes(body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Payflow-Signature", signatures.sign(p.getProvider(), raw));
        assertThat(
                        http.postForEntity(
                                        "/api/v1/webhooks/providers/provider-a",
                                        new HttpEntity<>(raw, headers),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(count("payment_reconciliation_issues")).isZero();
        assertThat(outbox.count()).isEqualTo(3);
    }

    @Test
    void changedProviderRefundPayloadConflictsWithDurableKey() {
        Payment p = captured("1000");
        var refund = create(p, "500");
        var request =
                new ProviderRefundRequest(
                        refund.id(),
                        p.getId(),
                        p.getProviderTransactionId(),
                        refund.amount(),
                        refund.currency(),
                        RefundStore.key(refund.id(), refund.provider()));
        var first = providerA.refundPayment(request);
        assertThatThrownBy(
                        () ->
                                providerA.refundPayment(
                                        new ProviderRefundRequest(
                                                refund.id(),
                                                p.getId(),
                                                p.getProviderTransactionId(),
                                                new BigDecimal("600"),
                                                refund.currency(),
                                                request.providerRefundIdempotencyKey())))
                .isInstanceOf(ProviderTechnicalException.class);
        assertThat(providerA.refundPayment(request).providerRefundId())
                .isEqualTo(first.providerRefundId());
        assertThat(count("mock_provider_refunds")).isEqualTo(1);
    }

    @Test
    void refundStatusCircuitOpenKeepsOutcomePending() {
        Payment p = captured("1000");
        var refund = pending(p, "1000", false);
        circuits.circuitBreaker("providerARefundStatus").transitionToOpenState();
        due(refund);
        poller.reconcileBatch();
        assertThat(service.get(refund.id()).status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        assertThat(outbox.count()).isEqualTo(3);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        never())
                .getRefundStatus(anyString());
    }

    @Test
    void corruptedRefundKafkaRecordGoesToDedicatedDlt() throws Exception {
        var listener = listeners.getListenerContainer("refundLifecycleListener");
        listener.start();
        var config = new java.util.Properties();
        config.put("bootstrap.servers", kafka.getBootstrapServers());
        config.put("group.id", "refund-dlt-test-" + UUID.randomUUID());
        config.put("auto.offset.reset", "earliest");
        config.put("enable.auto.commit", "false");
        config.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        config.put(
                "value.deserializer",
                "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        try (var consumer =
                new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(config)) {
            consumer.subscribe(List.of(RefundEventTopics.DLT));
            String key = "bad-refund-" + UUID.randomUUID();
            byte[] raw = "{malformed-refund".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            kafkaTemplate.send(RefundEventTopics.EVENTS, key, raw).get(10, TimeUnit.SECONDS);
            java.util.concurrent.atomic.AtomicReference<byte[]> recovered =
                    new java.util.concurrent.atomic.AtomicReference<>();
            await().atMost(Duration.ofSeconds(20))
                    .until(
                            () -> {
                                for (var record : consumer.poll(Duration.ofMillis(200)))
                                    if (record.key().equals(key)) recovered.set(record.value());
                                return recovered.get() != null;
                            });
            assertThat(recovered.get()).isEqualTo(raw);
        } finally {
            listener.stop();
        }
    }
}
