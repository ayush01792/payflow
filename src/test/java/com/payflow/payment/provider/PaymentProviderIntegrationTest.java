package com.payflow.payment.provider;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.ProviderProperties;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.mock.*;
import com.payflow.payment.outbox.OutboxPublisher;
import com.payflow.payment.repository.*;
import com.payflow.payment.service.*;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
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
            "payflow.providers.enabled=true",
            "payflow.reconciliation.enabled=false",
            "payflow.providers.connect-timeout=100ms",
            "payflow.providers.read-timeout=200ms",
            "resilience4j.retry.configs.providerDefault.wait-duration=30ms",
            "resilience4j.circuitbreaker.configs.providerDefault.sliding-window-size=3",
            "resilience4j.circuitbreaker.configs.providerDefault.minimum-number-of-calls=3",
            "resilience4j.circuitbreaker.configs.providerDefault.failure-rate-threshold=100",
            "resilience4j.circuitbreaker.configs.providerDefault.permitted-number-of-calls-in-half-open-state=1"
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@MockitoSpyBean(
        types = {
            MockProviderA.class,
            MockProviderB.class,
            ObjectMapper.class,
            PaymentOrchestrator.class
        })
class PaymentProviderIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.group-id", () -> "payflow-provider-integration");
    }

    private final PaymentRepository payments;
    private final PaymentProviderAttemptRepository attempts;
    private final MockProviderPaymentRepository mockPayments;
    private final OutboxEventRepository outbox;
    private final PaymentIdempotencyRepository idempotency;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentOrchestrator orchestrator;
    private final PaymentProviderRouter router;
    private final ProviderWorkflowStore workflow;
    private final ProviderInvocationService invocation;
    private final ProviderProperties properties;
    private final MockProviderControl controls;
    private final MockProviderA providerA;
    private final MockProviderB providerB;
    private final CircuitBreakerRegistry circuits;
    private final ObjectMapper mapper;
    private final TestRestTemplate http;
    private final StringRedisTemplate cache;
    private final OutboxPublisher publisher;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaListenerEndpointRegistry listeners;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    PaymentProviderIntegrationTest(
            PaymentRepository payments,
            PaymentProviderAttemptRepository attempts,
            MockProviderPaymentRepository mockPayments,
            OutboxEventRepository outbox,
            PaymentIdempotencyRepository idempotency,
            ProcessedKafkaEventRepository processed,
            PaymentOrchestrator orchestrator,
            PaymentProviderRouter router,
            ProviderWorkflowStore workflow,
            ProviderInvocationService invocation,
            ProviderProperties properties,
            MockProviderControl controls,
            MockProviderA providerA,
            MockProviderB providerB,
            CircuitBreakerRegistry circuits,
            ObjectMapper mapper,
            TestRestTemplate http,
            StringRedisTemplate cache,
            OutboxPublisher publisher,
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaListenerEndpointRegistry listeners,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.payments = payments;
        this.attempts = attempts;
        this.mockPayments = mockPayments;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.processed = processed;
        this.orchestrator = orchestrator;
        this.router = router;
        this.workflow = workflow;
        this.invocation = invocation;
        this.properties = properties;
        this.controls = controls;
        this.providerA = providerA;
        this.providerB = providerB;
        this.circuits = circuits;
        this.mapper = mapper;
        this.http = http;
        this.cache = cache;
        this.publisher = publisher;
        this.kafkaTemplate = kafkaTemplate;
        this.listeners = listeners;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void clean() {
        listeners.getListenerContainer("paymentLifecycleListener").stop();
        attempts.deleteAll();
        mockPayments.deleteAll();
        outbox.deleteAll();
        processed.deleteAll();
        idempotency.deleteAll();
        payments.deleteAll();
        cache.execute(
                (org.springframework.data.redis.core.RedisCallback<Void>)
                        c -> {
                            c.serverCommands().flushDb();
                            return null;
                        });
        controls.reset();
        properties.setABaseUrl("");
        properties.setBBaseUrl("");
        properties.setOrchestrationLease(Duration.ofSeconds(60));
        properties.getRouting().put(PaymentMethod.UPI, PaymentProviderType.PROVIDER_A);
        properties.getRouting().put(PaymentMethod.CARD, PaymentProviderType.PROVIDER_B);
        for (PaymentProviderType provider : PaymentProviderType.values())
            circuits.circuitBreaker(provider.resilienceName()).reset();
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
                .processPayment(any());
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
                .processPayment(any());
    }

    @AfterEach
    void stop() {
        listeners.getListenerContainer("paymentLifecycleListener").stop();
    }

    Payment processing(PaymentMethod method) {
        return payments.saveAndFlush(
                new Payment(
                        "merchant-" + UUID.randomUUID(),
                        "user-test",
                        new BigDecimal("12.5000"),
                        Currency.getInstance("USD"),
                        method,
                        PaymentStatus.PROCESSING));
    }

    PaymentEvent event(Payment p) {
        return PaymentEvent.forPayment(
                PaymentEventType.PAYMENT_PROCESSING,
                new PaymentResponse(
                        p.getId(),
                        p.getMerchantId(),
                        p.getUserId(),
                        p.getAmount(),
                        "USD",
                        p.getPaymentMethod(),
                        PaymentStatus.PROCESSING,
                        p.getCreatedAt(),
                        p.getUpdatedAt()));
    }

    Payment current(Payment p) {
        return payments.findById(p.getId()).orElseThrow();
    }

    long calls(PaymentProviderType provider) {
        return mockPayments.findAll().stream()
                .filter(row -> row.getProvider() == provider)
                .mapToLong(MockProviderPayment::getCallCount)
                .sum();
    }

    void scenario(PaymentProviderType provider, MockScenario scenario, int failFirst, int delayMs) {
        controls.configure(provider, new MockBehavior(scenario, failFirst, delayMs));
    }

    @Test
    void configurationDrivesRoutingAndSeparateFallback() {
        assertThat(router.primary(PaymentMethod.UPI).type())
                .isEqualTo(PaymentProviderType.PROVIDER_A);
        assertThat(router.primary(PaymentMethod.CARD).type())
                .isEqualTo(PaymentProviderType.PROVIDER_B);
        assertThat(router.fallback(PaymentProviderType.PROVIDER_A).type())
                .isEqualTo(PaymentProviderType.PROVIDER_B);
        properties.getRouting().put(PaymentMethod.CARD, PaymentProviderType.PROVIDER_A);
        assertThat(router.primary(PaymentMethod.CARD).type())
                .isEqualTo(PaymentProviderType.PROVIDER_A);
    }

    @Test
    void successStoresProviderAuditAndOutboxAndEvictsCache() throws Exception {
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        assertThat(
                        http.getForObject("/api/v1/payments/" + p.getId(), PaymentResponse.class)
                                .status())
                .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isTrue();
        orchestrator.handle(source);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(current(p).getProvider()).isEqualTo(PaymentProviderType.PROVIDER_A);
        assertThat(current(p).getProviderTransactionId()).isNotBlank();
        assertThat(attempts.findAll())
                .hasSize(1)
                .allMatch(
                        row ->
                                row.getStatus() == ProviderAttemptStatus.SUCCEEDED
                                        && row.getCompletedAt() != null
                                        && row.getAttemptNumber() == 1);
        assertThat(outbox.findAll())
                .hasSize(1)
                .allMatch(row -> row.getStatus() == OutboxStatus.PENDING);
        assertThat(
                        mapper.readValue(
                                        outbox.findAll().getFirst().getPayload(),
                                        PaymentEvent.class)
                                .eventType())
                .isEqualTo(PaymentEventType.PAYMENT_SUCCEEDED);
        assertThat(processed.existsById(source.eventId())).isTrue();
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isFalse();
        assertThat(
                        http.getForObject("/api/v1/payments/" + p.getId(), PaymentResponse.class)
                                .status())
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void declineIsNotRetriedOrFailedOverAndDoesNotTripCircuit() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.DECLINE, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(current(p).getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(attempts.findAll())
                .hasSize(1)
                .allMatch(row -> row.getStatus() == ProviderAttemptStatus.DECLINED);
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(1);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
        assertThat(circuits.circuitBreaker("providerA").getMetrics().getNumberOfFailedCalls())
                .isZero();
        assertThat(outbox.findAll().getFirst().getEventType()).isEqualTo("PAYMENT_FAILED");
    }

    @Test
    void transientServerFailuresRetrySameKeyAndSucceed() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 2, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        var rows =
                attempts.findAll().stream()
                        .sorted(Comparator.comparingInt(PaymentProviderAttempt::getAttemptNumber))
                        .toList();
        assertThat(rows)
                .extracting(PaymentProviderAttempt::getAttemptNumber)
                .containsExactly(1, 2, 3);
        assertThat(rows)
                .extracting(PaymentProviderAttempt::getStatus)
                .containsExactly(
                        ProviderAttemptStatus.TECHNICAL_FAILURE,
                        ProviderAttemptStatus.TECHNICAL_FAILURE,
                        ProviderAttemptStatus.SUCCEEDED);
        assertThat(rows)
                .extracting(PaymentProviderAttempt::getProviderIdempotencyKey)
                .containsOnly("payflow:" + p.getId() + ":PROVIDER_A");
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(3);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
    }

    @Test
    void provenPreExecutionFailureFailsOverToSecondary() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(current(p).getProvider()).isEqualTo(PaymentProviderType.PROVIDER_B);
        assertThat(attempts.findAll()).hasSize(4);
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(3);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isEqualTo(1);
        assertThat(mockPayments.findAll().stream().filter(row -> row.getResult() != null).count())
                .isEqualTo(1);
    }

    @Test
    void bothDefinitivelyUnavailableProvidersProduceTechnicalFailure() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 0, 0);
        scenario(PaymentProviderType.PROVIDER_B, MockScenario.HTTP_500, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(current(p).getFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(attempts.count()).isEqualTo(6);
        assertThat(outbox.findAll().getFirst().getEventType()).isEqualTo("PAYMENT_FAILED");
    }

    @ParameterizedTest
    @EnumSource(
            value = MockScenario.class,
            names = {"HTTP_400", "HTTP_401", "HTTP_403"})
    void clientRejectionsDoNotRetryFailOverOrTripCircuit(MockScenario behavior) {
        scenario(PaymentProviderType.PROVIDER_A, behavior, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(current(p).getFailureCode()).isEqualTo("PROVIDER_REQUEST_REJECTED");
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
        assertThat(circuits.circuitBreaker("providerA").getMetrics().getNumberOfFailedCalls())
                .isZero();
    }

    @Test
    void lostSuccessResponseRetriesSameProviderWithoutSecondLogicalPayment() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.SLOW_SUCCESS, 0, 700);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(attempts.findAll()).hasSize(2);
        assertThat(
                        attempts.findAll().stream()
                                .filter(PaymentProviderAttempt::isResponseAmbiguous)
                                .count())
                .isEqualTo(1);
        assertThat(mockPayments.count()).isEqualTo(1);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
    }

    @Test
    void unresolvedTimeoutDoesNotChargeAnotherProviderOrClaimDefiniteFailure() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.TIMEOUT, 0, 700);
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        http.getForObject("/api/v1/payments/" + p.getId(), PaymentResponse.class);
        orchestrator.handle(source);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(current(p).getFailureCode()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(attempts.findAll())
                .hasSize(3)
                .allMatch(
                        row ->
                                row.isResponseAmbiguous()
                                        && row.getStatus()
                                                == ProviderAttemptStatus.TECHNICAL_FAILURE);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
        assertThat(mockPayments.findAll()).hasSize(1).allMatch(row -> row.getResult() != null);
        assertThat(outbox.count()).isZero();
        assertThat(processed.existsById(source.eventId())).isTrue();
        assertThat(cache.hasKey("payflow:payment:" + p.getId())).isFalse();
        assertThat(
                        http.getForObject("/api/v1/payments/" + p.getId(), PaymentResponse.class)
                                .status())
                .isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
    }

    @Test
    void circuitOpensFailsFastAndRecoversThroughHalfOpen() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 0, 0);
        orchestrator.handle(event(processing(PaymentMethod.UPI)));
        CircuitBreaker circuit = circuits.circuitBreaker("providerA");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long before = calls(PaymentProviderType.PROVIDER_A);
        orchestrator.handle(event(processing(PaymentMethod.UPI)));
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(before);
        assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isPositive();
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.SUCCESS, 0, 0);
        circuit.transitionToHalfOpenState();
        Payment recovery = processing(PaymentMethod.UPI);
        orchestrator.handle(event(recovery));
        assertThat(current(recovery).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(current(recovery).getProvider()).isEqualTo(PaymentProviderType.PROVIDER_A);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void concurrentProviderRequestsReuseOnePersistedTransaction() throws Exception {
        Payment p = processing(PaymentMethod.UPI);
        ProviderPaymentRequest request =
                new ProviderPaymentRequest(
                        p.getId(),
                        p.getMerchantId(),
                        p.getUserId(),
                        p.getAmount(),
                        "USD",
                        p.getPaymentMethod());
        List<Future<ProviderPaymentResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> providerA.processPayment(request)));
            List<String> transactionIds = new ArrayList<>();
            for (var future : futures)
                transactionIds.add(future.get(10, TimeUnit.SECONDS).providerTransactionId());
            assertThat(new HashSet<>(transactionIds)).hasSize(1);
        }
        assertThat(mockPayments.count()).isEqualTo(1);
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(8);
        ProviderPaymentRequest changed =
                new ProviderPaymentRequest(
                        p.getId(),
                        p.getMerchantId(),
                        p.getUserId(),
                        BigDecimal.TEN,
                        "USD",
                        p.getPaymentMethod());
        assertThatThrownBy(() -> providerA.processPayment(changed))
                .isInstanceOf(ProviderTechnicalException.class);
        assertThat(mockPayments.count()).isEqualTo(1);
    }

    @Test
    void duplicateProcessingEventNeverRepeatsProviderOrTerminalOutbox() {
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        orchestrator.handle(source);
        orchestrator.handle(source);
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.DECLINE, 0, 0);
        orchestrator.handle(event(p));
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(mockPayments.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void terminalDeclineCannotBeReversedByRedelivery() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.DECLINE, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.SUCCESS, 0, 0);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = MockScenario.class,
            names = {"SUCCESS", "DECLINE"})
    void localFinalizationFailureRollsBackStateOutboxAndMarkerThenSafelyRecovers(
            MockScenario behavior) throws Exception {
        scenario(PaymentProviderType.PROVIDER_A, behavior, 0, 0);
        PaymentStatus expectedStatus =
                behavior == MockScenario.SUCCESS ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        PaymentEventType expectedEvent =
                behavior == MockScenario.SUCCESS
                        ? PaymentEventType.PAYMENT_SUCCEEDED
                        : PaymentEventType.PAYMENT_FAILED;
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        doThrow(new JsonProcessingException("controlled final event failure") {})
                .when(mapper)
                .writeValueAsString(
                        argThat(
                                value ->
                                        value instanceof PaymentEvent e
                                                && e.eventType() == expectedEvent));
        assertThatThrownBy(() -> orchestrator.handle(source))
                .isInstanceOf(IllegalStateException.class);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(current(p).getOrchestrationToken()).isNull();
        assertThat(processed.existsById(source.eventId())).isFalse();
        assertThat(outbox.count()).isZero();
        assertThat(attempts.findAll().getFirst().getStatus())
                .isEqualTo(
                        behavior == MockScenario.SUCCESS
                                ? ProviderAttemptStatus.SUCCEEDED
                                : ProviderAttemptStatus.DECLINED);
        String providerTransactionId = attempts.findAll().getFirst().getProviderTransactionId();
        doCallRealMethod().when(mapper).writeValueAsString(any());
        circuits.circuitBreaker("providerA").transitionToOpenState();
        orchestrator.handle(source);
        assertThat(current(p).getStatus()).isEqualTo(expectedStatus);
        assertThat(current(p).getProviderTransactionId()).isEqualTo(providerTransactionId);
        assertThat(current(p).getProvider()).isEqualTo(PaymentProviderType.PROVIDER_A);
        assertThat(attempts.count()).isEqualTo(1);
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA),
                        times(1))
                .processPayment(any());
        verify(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderB>getUltimateTargetObject(providerB),
                        never())
                .processPayment(any());
        assertThat(mockPayments.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void activeClaimPreventsIndependentConcurrentExecution() throws Exception {
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent first = event(p);
        PaymentEvent other = event(p);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            started.countDown();
                            if (!release.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test barrier timeout");
                            return call.callRealMethod();
                        })
                .when(
                        org.springframework.test.util.AopTestUtils
                                .<MockProviderA>getUltimateTargetObject(providerA))
                .processPayment(any());
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> task = pool.submit(() -> orchestrator.handle(first));
            try {
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> orchestrator.handle(other))
                        .isInstanceOf(ProviderWorkInProgressException.class);
            } finally {
                release.countDown();
            }
            task.get(10, TimeUnit.SECONDS);
        }
        orchestrator.handle(other);
        assertThat(attempts.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void priorInterruptedAttemptPreventsUnsafeFailoverAfterRecovery() {
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        ProviderClaim interrupted = workflow.claim(source).orElseThrow();
        workflow.startAttempt(interrupted, PaymentProviderType.PROVIDER_A);
        workflow.releaseAfterLocalFailure(interrupted);
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 0, 0);
        orchestrator.handle(source);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isZero();
    }

    @Test
    void creationOutboxKafkaProviderAndTerminalOutboxWorkTogether() throws Exception {
        var headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var body =
                Map.of(
                        "merchantId",
                        "merchant-e2e",
                        "userId",
                        "user-test",
                        "amount",
                        "12.50",
                        "currency",
                        "USD",
                        "paymentMethod",
                        "CARD");
        var response =
                http.postForEntity(
                        "/api/v1/payments", new HttpEntity<>(body, headers), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.CREATED);
        UUID id = response.getBody().id();
        assertThat(outbox.findAll())
                .hasSize(1)
                .allMatch(row -> row.getStatus() == OutboxStatus.PENDING);
        listeners.getListenerContainer("paymentLifecycleListener").start();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            publisher.publishPending();
                            assertThat(payments.findById(id).orElseThrow().getStatus())
                                    .isEqualTo(PaymentStatus.SUCCESS);
                            assertThat(outbox.findAll())
                                    .hasSize(3)
                                    .allMatch(row -> row.getStatus() == OutboxStatus.PUBLISHED);
                            assertThat(processed.count()).isEqualTo(3);
                        });
        PaymentEvent trigger =
                mapper.readValue(
                        outbox.findAll().stream()
                                .filter(row -> row.getEventType().equals("PAYMENT_PROCESSING"))
                                .findFirst()
                                .orElseThrow()
                                .getPayload(),
                        PaymentEvent.class);
        kafkaTemplate
                .send(PaymentEventTopics.EVENTS, id.toString(), trigger)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate
                .send(PaymentEventTopics.EVENTS, id.toString(), trigger)
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            verify(
                                            org.springframework.test.util.AopTestUtils
                                                    .<PaymentOrchestrator>getUltimateTargetObject(
                                                            orchestrator),
                                            times(3))
                                    .handle(trigger);
                            assertThat(attempts.count()).isEqualTo(1);
                            assertThat(outbox.count()).isEqualTo(3);
                        });
        assertThat(http.getForObject("/api/v1/payments/" + id, PaymentResponse.class).status())
                .isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void connectionRefusalRetriesThenSafelyFailsOver() {
        properties.setABaseUrl("http://127.0.0.1:1/mock/providers/provider-a");
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(current(p).getProvider()).isEqualTo(PaymentProviderType.PROVIDER_B);
        assertThat(
                        attempts.findAll().stream()
                                .filter(row -> row.getProvider() == PaymentProviderType.PROVIDER_A)
                                .toList())
                .hasSize(3)
                .allMatch(
                        row ->
                                !row.isResponseAmbiguous()
                                        && row.getFailureCode()
                                                .equals("PROVIDER_CONNECTION_FAILED"));
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isZero();
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isEqualTo(1);
    }

    @Test
    void expiredLeaseRecoveryFencesOldWorkerAndReusesProviderKey() {
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        ProviderClaim old = workflow.claim(source).orElseThrow();
        jdbc.update(
                "UPDATE payments SET orchestration_lease_until = CURRENT_TIMESTAMP - INTERVAL '1"
                        + " second' WHERE id = ?",
                p.getId());
        ProviderClaim recovered = workflow.claim(source).orElseThrow();
        assertThat(recovered.token()).isNotEqualTo(old.token());
        assertThat(recovered.request().idempotencyKey(recovered.provider()))
                .isEqualTo(old.request().idempotencyKey(old.provider()));
        assertThatThrownBy(() -> workflow.startAttempt(old, PaymentProviderType.PROVIDER_A))
                .isInstanceOf(ProviderWorkInProgressException.class);
        workflow.releaseAfterLocalFailure(old);
        assertThat(current(p).getOrchestrationToken()).isEqualTo(recovered.token());
        ProviderPaymentResult result =
                invocation.invoke(recovered, router.provider(recovered.provider()));
        workflow.finishResult(recovered, result);
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(attempts.count()).isEqualTo(1);
    }

    @Test
    void fallbackDeclineDoesNotRestartPrimaryOrRetryDecline() {
        scenario(PaymentProviderType.PROVIDER_A, MockScenario.HTTP_500, 0, 0);
        scenario(PaymentProviderType.PROVIDER_B, MockScenario.DECLINE, 0, 0);
        Payment p = processing(PaymentMethod.UPI);
        orchestrator.handle(event(p));
        assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(current(p).getFailureCode()).isEqualTo("CARD_DECLINED");
        assertThat(calls(PaymentProviderType.PROVIDER_A)).isEqualTo(3);
        assertThat(calls(PaymentProviderType.PROVIDER_B)).isEqualTo(1);
        assertThat(attempts.count()).isEqualTo(4);
    }

    @Test
    void mockOnlyHttpControlsDoNotChangeOriginalIdempotentDecline() {
        http.put(
                "/mock/providers/provider-a/behavior",
                new MockBehavior(MockScenario.DECLINE, 0, 0));
        Payment p = processing(PaymentMethod.UPI);
        ProviderPaymentRequest request =
                new ProviderPaymentRequest(
                        p.getId(),
                        p.getMerchantId(),
                        p.getUserId(),
                        p.getAmount(),
                        "USD",
                        p.getPaymentMethod());
        ProviderPaymentResult original = providerA.processPayment(request);
        assertThat(original.outcome()).isEqualTo(ProviderPaymentOutcome.DECLINED);
        http.put("/mock/providers/provider-a/behavior", MockBehavior.success());
        assertThat(providerA.processPayment(request)).isEqualTo(original);
        assertThat(mockPayments.count()).isEqualTo(1);
    }

    @Test
    void kafkaRedeliveryWaitsForCrashedOwnerLeaseAndRecovers() throws Exception {
        listeners.getListenerContainer("paymentLifecycleListener").start();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                listeners
                                                        .getListenerContainer(
                                                                "paymentLifecycleListener")
                                                        .getAssignedPartitions())
                                        .hasSize(3));
        properties.setOrchestrationLease(Duration.ofMillis(800));
        Payment p = processing(PaymentMethod.UPI);
        PaymentEvent source = event(p);
        workflow.claim(source)
                .orElseThrow(); // Simulate a worker crash immediately after claim, before HTTP.
        kafkaTemplate
                .send(PaymentEventTopics.EVENTS, p.getId().toString(), source)
                .get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(current(p).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
                            assertThat(processed.existsById(source.eventId())).isTrue();
                            verify(
                                            org.springframework.test.util.AopTestUtils
                                                    .<PaymentOrchestrator>getUltimateTargetObject(
                                                            orchestrator),
                                            atLeast(2))
                                    .handle(source);
                            assertThat(attempts.count()).isEqualTo(1);
                            assertThat(mockPayments.count()).isEqualTo(1);
                        });
    }
}
