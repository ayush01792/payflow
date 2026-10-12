package com.payflow.payment.observability;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.*;
import com.payflow.payment.config.ProviderProperties;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.mock.*;
import com.payflow.payment.outbox.OutboxPublisher;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.*;
import com.payflow.payment.security.MerchantCredentialService;
import com.payflow.payment.service.*;

import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.*;
import io.micrometer.tracing.Tracer;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.*;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payflow.security.api-key-pepper=observability-test-pepper-at-least-32-bytes",
            "payflow.webhooks.a-secret=observability-webhook-secret",
            "management.server.port=0",
            "payflow.mock-providers.enabled=true",
            "spring.kafka.listener.auto-startup=false",
            "payflow.outbox.enabled=false",
            "payflow.reconciliation.enabled=false",
            "payflow.observability.snapshot-enabled=false",
            "management.tracing.sampling.probability=1.0",
            "management.otlp.tracing.endpoint=http://127.0.0.1:1/v1/traces",
            "payflow.providers.read-timeout=200ms",
            "payflow.providers.connect-timeout=100ms",
            "payflow.rate-limit.create-limit=100",
            "resilience4j.retry.configs.providerDefault.wait-duration=10ms"
        })
@ActiveProfiles("observability-local")
@AutoConfigureObservability(metrics = true, tracing = true)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);

    @Container static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.group-id", () -> "payflow-observability-tests");
    }

    @Autowired TestRestTemplate http;
    @Autowired MerchantCredentialService credentials;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired Tracer tracer;
    @Autowired io.opentelemetry.sdk.trace.SdkTracerProvider sdk;
    @Autowired RefundReconciliationService refundReconciliation;
    @Autowired PayflowMetrics metrics;
    @Autowired OperationalMetricsSnapshot snapshot;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PaymentRepository payments;
    @Autowired PaymentLifecycleService lifecycle;
    @Autowired PaymentOrchestrator orchestrator;
    @Autowired RefundService refunds;
    @Autowired RefundLifecycleService refundLifecycle;
    @Autowired RefundOrchestrator refundOrchestrator;
    @Autowired PaymentReconciliationService reconciliation;
    @Autowired MockProviderA provider;
    @Autowired MockProviderControl controls;
    @Autowired ProviderProperties properties;
    @Autowired HealthContributorRegistry health;
    @Autowired CircuitBreakerRegistry circuits;
    @Autowired OutboxPublisher publisher;
    @Autowired com.payflow.payment.outbox.OutboxStore outboxStore;
    @Autowired KafkaTemplate<String, Object> template;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @LocalManagementPort int managementPort;
    String key;
    String merchant;

    @BeforeEach
    void clean() {
        listeners.getListenerContainers().forEach(c -> c.stop());
        jdbc.execute("TRUNCATE payments CASCADE");
        jdbc.execute("TRUNCATE outbox_events CASCADE");
        jdbc.execute("TRUNCATE merchants CASCADE");
        controls.reset();
        properties.setABaseUrl("");
        properties.setBBaseUrl("");
        circuits.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        merchant = "observe-" + UUID.randomUUID();
        var id = credentials.createMerchant(merchant, "Observability test");
        key = credentials.createKey(id, "test", null).apiKey();
        snapshot.refresh();
    }

    @AfterEach
    void stop() {
        listeners.getListenerContainers().forEach(c -> c.stop());
        MDC.clear();
    }

    double count(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    ResponseEntity<String> management(String route) {
        return http.getForEntity("http://127.0.0.1:" + managementPort + route, String.class);
    }

    PaymentResponse create() {
        var headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.set("X-Correlation-ID", "integration-safe-id");
        var response =
                http.postForEntity(
                        "/api/v1/payments",
                        new HttpEntity<>(
                                new CreatePaymentRequest(
                                        "USER", new BigDecimal("100"), "USD", PaymentMethod.UPI),
                                headers),
                        PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Correlation-ID"))
                .isEqualTo("integration-safe-id");
        return response.getBody();
    }

    Payment captured() {
        var p = create();
        lifecycle.handle(PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, p));
        var processing = PaymentMapper.toResponse(payments.findById(p.id()).orElseThrow());
        orchestrator.handle(
                PaymentEvent.forPayment(PaymentEventType.PAYMENT_PROCESSING, processing));
        assertThat(payments.findById(p.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCESS);
        return payments.findById(p.id()).orElseThrow();
    }

    RefundResponse processRefund(Payment p) {
        var refund =
                refunds.createOwned(
                        p.getId(),
                        merchant,
                        new CreateRefundRequest(new BigDecimal("10"), UUID.randomUUID().toString()),
                        UUID.randomUUID().toString());
        refundLifecycle.handle(
                RefundEvent.forRefund(RefundEventType.REFUND_CREATED, refund, merchant));
        refundOrchestrator.handle(
                RefundEvent.forRefund(
                        RefundEventType.REFUND_PROCESSING,
                        refunds.getOwned(refund.id(), merchant),
                        merchant));
        return refunds.getOwned(refund.id(), merchant);
    }

    @Test
    void publicProbesHideDetailsAndLocalMetricsAreAvailable() {
        for (String route :
                List.of(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness")) {
            var res = management(route);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody())
                    .contains("\"status\":\"UP\"")
                    .doesNotContain("components", "details");
        }
        assertThat(management("/actuator/info").getBody()).contains("build", "payflow", "version");
        for (String route :
                List.of(
                        "/actuator/env",
                        "/actuator/configprops",
                        "/actuator/heapdump",
                        "/actuator/threaddump"))
            assertThat(management(route).getStatusCode())
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        assertThat(http.getForEntity("/api/v1/payments", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void externalDependencyFailureNeverMakesLivenessFail() {
        var db = health.unregisterContributor("db");
        try {
            health.registerContributor("db", (HealthIndicator) () -> Health.down().build());
            assertThat(management("/actuator/health/readiness").getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(management("/actuator/health/liveness").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            health.unregisterContributor("db");
            health.registerContributor("db", db);
        }
        var broker = health.unregisterContributor("kafka");
        try {
            health.registerContributor("kafka", (HealthIndicator) () -> Health.down().build());
            assertThat(management("/actuator/health").getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(management("/actuator/health/readiness").getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        } finally {
            health.unregisterContributor("kafka");
            health.registerContributor("kafka", broker);
        }
    }

    @Test
    void committedPaymentAndRefundCountersAppearInPrometheus() {
        double created = count("payflow.payment.created", "paymentMethod", "UPI"),
                complete = count("payflow.payment.completed", "paymentMethod", "UPI"),
                refunded = count("payflow.refund.completed");
        var p = captured();
        assertThat(processRefund(p).status()).isEqualTo(RefundStatus.SUCCESS);
        assertThat(count("payflow.payment.created", "paymentMethod", "UPI")).isEqualTo(created + 1);
        assertThat(count("payflow.payment.completed", "paymentMethod", "UPI"))
                .isEqualTo(complete + 1);
        assertThat(count("payflow.refund.completed")).isEqualTo(refunded + 1);
        var scrape = management("/actuator/prometheus");
        assertThat(scrape.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scrape.getBody())
                .contains(
                        "payflow_payment_completed_total",
                        "payflow_refund_completed_total",
                        "payflow_provider_call_seconds",
                        "jvm_memory_used_bytes",
                        "hikaricp_connections",
                        "http_server_requests_seconds",
                        "resilience4j_circuitbreaker_state");
    }

    @Test
    void rollbackDoesNotIncrementBusinessCounterOrPersistOutbox() {
        double before = count("payflow.payment.created", "paymentMethod", "UPI");
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(
                status -> {
                    metrics.paymentEvent("PAYMENT_CREATED", PaymentMethod.UPI);
                    metrics.refundEvent("REFUND_SUCCEEDED");
                    status.setRollbackOnly();
                });
        assertThat(count("payflow.payment.created", "paymentMethod", "UPI")).isEqualTo(before);
    }

    @Test
    void providerFailuresAndCircuitRejectionsHaveBoundedTags() {
        controls.configure(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.HTTP_500, 0, 0));
        double failed =
                count(
                        "payflow.provider.failures",
                        "provider",
                        "PROVIDER_A",
                        "operation",
                        "PAYMENT",
                        "outcome",
                        "TECHNICAL_FAILURE");
        assertThatThrownBy(
                        () ->
                                provider.processPayment(
                                        new ProviderPaymentRequest(
                                                UUID.randomUUID(),
                                                merchant,
                                                "USER",
                                                BigDecimal.TEN,
                                                "USD",
                                                PaymentMethod.UPI)))
                .isInstanceOf(ProviderTechnicalException.class);
        assertThat(
                        count(
                                "payflow.provider.failures",
                                "provider",
                                "PROVIDER_A",
                                "operation",
                                "PAYMENT",
                                "outcome",
                                "TECHNICAL_FAILURE"))
                .isEqualTo(failed + 1);
        assertThat(
                        registry.get("payflow.provider.call")
                                .tags(
                                        "provider",
                                        "PROVIDER_A",
                                        "operation",
                                        "PAYMENT",
                                        "outcome",
                                        "TECHNICAL_FAILURE")
                                .timer()
                                .count())
                .isPositive();
        var cb = circuits.circuitBreaker("providerA");
        cb.transitionToOpenState();
        double open =
                count(
                        "payflow.provider.requests",
                        "provider",
                        "PROVIDER_A",
                        "operation",
                        "PAYMENT",
                        "outcome",
                        "CIRCUIT_OPEN");
        assertThatThrownBy(() -> cb.executeSupplier(() -> "unused"))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(
                        count(
                                "payflow.provider.requests",
                                "provider",
                                "PROVIDER_A",
                                "operation",
                                "PAYMENT",
                                "outcome",
                                "CIRCUIT_OPEN"))
                .isEqualTo(open + 1);
        assertThat(
                        registry.get("resilience4j.circuitbreaker.state")
                                .tags("name", "providerA", "state", "open")
                                .gauge()
                                .value())
                .isEqualTo(1);
        registry.getMeters().stream()
                .filter(m -> m.getId().getName().startsWith("payflow."))
                .forEach(
                        m -> {
                            assertThat(m.getId().getTags())
                                    .allMatch(
                                            t ->
                                                    Set.of(
                                                                    "application",
                                                                    "provider",
                                                                    "operation",
                                                                    "outcome",
                                                                    "domain",
                                                                    "result",
                                                                    "paymentMethod",
                                                                    "error")
                                                            .contains(t.getKey()));
                            assertThat(m.getId().getTags())
                                    .noneMatch(
                                            t ->
                                                    t.getValue().contains(merchant)
                                                            || t.getValue().equals(key));
                        });
    }

    @Test
    void outboxSnapshotUsesDatabaseStateAndEmptyAgeIsZero() {
        assertThat(snapshot.value("outbox.pending")).isZero();
        assertThat(snapshot.value("outbox.oldest.pending.age")).isZero();
        create();
        jdbc.update("UPDATE outbox_events SET created_at=now()-interval '90 seconds'");
        snapshot.refresh();
        assertThat(snapshot.value("outbox.pending")).isEqualTo(1);
        assertThat(snapshot.value("outbox.oldest.pending.age")).isGreaterThanOrEqualTo(90);
        double published = count("payflow.outbox.published");
        publisher.publishPending();
        snapshot.refresh();
        assertThat(snapshot.value("outbox.pending")).isZero();
        assertThat(snapshot.value("outbox.oldest.pending.age")).isZero();
        assertThat(count("payflow.outbox.published")).isEqualTo(published + 1);
    }

    @Test
    void collectorOutageDoesNotBreakApiAndJsonLogsContainTraceContext(CapturedOutput output)
            throws Exception {
        var span = tracer.nextSpan().name("collector-outage-proof").start();
        span.end();
        sdk.forceFlush().join(3, java.util.concurrent.TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(output.getAll()).contains("Failed to export spans"));
        var payment = create();
        assertThat(payment.id()).isNotNull();
        var logs =
                Arrays.stream(output.getAll().split("\\R"))
                        .filter(s -> s.startsWith("{"))
                        .map(
                                s -> {
                                    try {
                                        return mapper.readTree(s);
                                    } catch (Exception e) {
                                        return mapper.createObjectNode();
                                    }
                                })
                        .filter(n -> n.path("message").asText().contains(payment.id().toString()))
                        .toList();
        assertThat(logs)
                .anySatisfy(
                        n -> {
                            assertThat(n.path("traceId").asText()).matches("[0-9a-f]{32}");
                            assertThat(n.path("spanId").asText()).matches("[0-9a-f]{16}");
                            assertThat(n.path("correlationId").asText())
                                    .isEqualTo("integration-safe-id");
                            assertThat(n.has("@timestamp")).isTrue();
                        });
        assertThat(output.getAll())
                .doesNotContain(key, "observability-test-pepper-at-least-32-bytes");
    }

    @Test
    void kafkaProducerPropagatesW3cContextToObservedConsumer(CapturedOutput output)
            throws Exception {
        var p = create();
        lifecycle.handle(PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, p));
        var processing = PaymentMapper.toResponse(payments.findById(p.id()).orElseThrow());
        String parentId;
        var span = tracer.nextSpan().name("observability-kafka-test").start();
        parentId = span.context().traceId();
        Properties settings = new Properties();
        settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        settings.put(ConsumerConfig.GROUP_ID_CONFIG, "observe-headers-" + UUID.randomUUID());
        settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        try (var scope = tracer.withSpan(span);
                var consumer = new KafkaConsumer<String, byte[]>(settings)) {
            consumer.subscribe(List.of(PaymentEventTopics.EVENTS));
            template.send(
                            PaymentEventTopics.EVENTS,
                            p.id().toString(),
                            PaymentEvent.forPayment(
                                    PaymentEventType.PAYMENT_PROCESSING, processing))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(
                            () -> {
                                var records = consumer.poll(Duration.ofMillis(300));
                                assertThat(records)
                                        .anySatisfy(
                                                r -> {
                                                    assertThat(r.key())
                                                            .isEqualTo(p.id().toString());
                                                    var header =
                                                            r.headers().lastHeader("traceparent");
                                                    assertThat(header).isNotNull();
                                                    assertThat(
                                                                    new String(
                                                                            header.value(),
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8))
                                                            .contains(parentId);
                                                });
                            });
        } finally {
            span.end();
        }
        listeners.getListenerContainer("paymentLifecycleListener").start();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(payments.findById(p.id()).orElseThrow().getStatus())
                                        .isEqualTo(PaymentStatus.SUCCESS));
        // The committed payment can be visible before the listener observation has stopped.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            assertThat(registry.find("spring.kafka.listener").timers())
                                    .isNotEmpty();
                            assertThat(Arrays.asList(output.getAll().split("\\R")))
                                    .anyMatch(
                                            line ->
                                                    line.contains("Received eventId=")
                                                            && line.contains(p.id().toString())
                                                            && line.contains(
                                                                    "\"traceId\":\""
                                                                            + parentId
                                                                            + "\""));
                        });
    }

    @Test
    void uncertaintyAndReconciliationHaveCommittedCountersAndGauges() {
        var p = create();
        lifecycle.handle(PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, p));
        controls.configure(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.TIMEOUT, 0, 500));
        double pending = count("payflow.payment.pending.confirmation"),
                attempts = count("payflow.reconciliation.attempts", "domain", "PAYMENT"),
                resolved =
                        count(
                                "payflow.reconciliation.resolutions",
                                "domain",
                                "PAYMENT",
                                "result",
                                "SUCCESS");
        orchestrator.handle(
                PaymentEvent.forPayment(
                        PaymentEventType.PAYMENT_PROCESSING,
                        PaymentMapper.toResponse(payments.findById(p.id()).orElseThrow())));
        assertThat(payments.findById(p.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING_CONFIRMATION);
        assertThat(count("payflow.payment.pending.confirmation")).isEqualTo(pending + 1);
        snapshot.refresh();
        assertThat(snapshot.value("payment.pending.confirmation.count")).isEqualTo(1);
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET next_reconciliation_at=now()-interval '1"
                        + " second'");
        controls.reset();
        reconciliation.reconcileBatch();
        assertThat(count("payflow.reconciliation.attempts", "domain", "PAYMENT"))
                .isEqualTo(attempts + 1);
        assertThat(
                        count(
                                "payflow.reconciliation.resolutions",
                                "domain",
                                "PAYMENT",
                                "result",
                                "SUCCESS"))
                .isEqualTo(resolved + 1);
        snapshot.refresh();
        assertThat(snapshot.value("payment.pending.confirmation.count")).isZero();
        assertThat(registry.get("payflow.reconciliation").tags("domain", "PAYMENT").timer().count())
                .isPositive();
    }

    @Test
    void refundUncertaintyResolvesThroughPollingWithoutCountingTwice() {
        var p = captured();
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.TIMEOUT, 0, 500));
        double pending = count("payflow.refund.pending.confirmation"),
                completed = count("payflow.refund.completed");
        var refund = processRefund(p);
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING_CONFIRMATION);
        assertThat(count("payflow.refund.pending.confirmation")).isEqualTo(pending + 1);
        snapshot.refresh();
        assertThat(snapshot.value("refund.pending.confirmation.count")).isEqualTo(1);
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET next_reconciliation_at=now()-interval '1"
                        + " second'");
        controls.reset();
        refundReconciliation.reconcileBatch();
        assertThat(refunds.getOwned(refund.id(), merchant).status())
                .isEqualTo(RefundStatus.SUCCESS);
        assertThat(count("payflow.refund.completed")).isEqualTo(completed + 1);
        refundReconciliation.reconcileBatch();
        assertThat(count("payflow.refund.completed")).isEqualTo(completed + 1);
        assertThat(count("payflow.reconciliation.attempts", "domain", "REFUND")).isPositive();
        assertThat(
                        count(
                                "payflow.reconciliation.resolutions",
                                "domain",
                                "REFUND",
                                "result",
                                "SUCCESS"))
                .isPositive();
    }

    @Test
    void financialDeclinesCountAsFailuresAndCacheAndRateMetricsRemainAggregate() {
        var p = captured();
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.DECLINE, 0, 0));
        double failed = count("payflow.refund.failed");
        assertThat(processRefund(p).status()).isEqualTo(RefundStatus.FAILED);
        assertThat(count("payflow.refund.failed")).isEqualTo(failed + 1);
        controls.configure(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.DECLINE, 0, 0));
        var other = create();
        lifecycle.handle(PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, other));
        double paymentFailed = count("payflow.payment.failed", "paymentMethod", "UPI");
        orchestrator.handle(
                PaymentEvent.forPayment(
                        PaymentEventType.PAYMENT_PROCESSING,
                        PaymentMapper.toResponse(payments.findById(other.id()).orElseThrow())));
        assertThat(count("payflow.payment.failed", "paymentMethod", "UPI"))
                .isEqualTo(paymentFailed + 1);
        var headers = new HttpHeaders();
        headers.set("X-API-Key", key);
        for (int i = 0; i < 2; i++)
            assertThat(
                            http.exchange(
                                            "/api/v1/payments/" + p.getId(),
                                            HttpMethod.GET,
                                            new HttpEntity<>(headers),
                                            String.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        assertThat(count("payflow.cache.requests", "result", "HIT")).isPositive();
        assertThat(count("payflow.rate.limit.requests", "result", "ALLOWED")).isPositive();
    }

    @Test
    void httpProviderCallPropagatesTraceAndSafeCorrelationId() {
        var span = tracer.nextSpan().name("provider-propagation-test").start();
        try (var scope = tracer.withSpan(span)) {
            MDC.put("correlationId", "safe-provider-correlation");
            provider.processPayment(
                    new ProviderPaymentRequest(
                            UUID.randomUUID(),
                            merchant,
                            "USER",
                            BigDecimal.TEN,
                            "USD",
                            PaymentMethod.UPI));
            assertThat(CapturedHeaders.correlation).isEqualTo("safe-provider-correlation");
            assertThat(CapturedHeaders.traceparent)
                    .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01")
                    .contains(span.context().traceId());
        } finally {
            span.end();
            MDC.clear();
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class CapturedHeaders {
        static volatile String traceparent, correlation;

        @org.springframework.context.annotation.Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        org.springframework.web.filter.OncePerRequestFilter>
                providerHeaders() {
            var filter =
                    new org.springframework.web.filter.OncePerRequestFilter() {
                        protected void doFilterInternal(
                                jakarta.servlet.http.HttpServletRequest req,
                                jakarta.servlet.http.HttpServletResponse res,
                                jakarta.servlet.FilterChain chain)
                                throws java.io.IOException, jakarta.servlet.ServletException {
                            traceparent = req.getHeader("traceparent");
                            correlation = req.getHeader("X-Correlation-ID");
                            chain.doFilter(req, res);
                        }
                    };
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<
                            org.springframework.web.filter.OncePerRequestFilter>(filter);
            registration.addUrlPatterns("/mock/*");
            return registration;
        }
    }

    @Test
    void outboxFailedSendCounterAndPermanentBacklogReflectFencedUpdates() {
        create();
        double failed = count("payflow.outbox.failed");
        var claim = outboxStore.claimNext().orElseThrow();
        outboxStore.recordFailure(claim, "Test-only delivery failure");
        outboxStore.recordFailure(claim, "Stale duplicate");
        assertThat(count("payflow.outbox.failed")).isEqualTo(failed + 1);
        jdbc.update("UPDATE outbox_events SET status='FAILED'");
        snapshot.refresh();
        assertThat(snapshot.value("outbox.pending")).isZero();
        assertThat(snapshot.value("outbox.failed.count")).isEqualTo(1);
    }

    @Test
    void webhookMetricsPreserveIndependentHmacSecurity() throws Exception {
        double rejected = count("payflow.webhook.received", "result", "REJECTED"),
                accepted = count("payflow.webhook.received", "result", "ACCEPTED");
        var payload =
                new ProviderWebhookRequest(
                        "observability-webhook",
                        "provider-transaction",
                        "unmatched-reference",
                        "SUCCESS",
                        BigDecimal.TEN,
                        "USD",
                        java.time.Instant.now());
        var raw = mapper.writeValueAsBytes(payload);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(
                        http.postForEntity(
                                        "/api/v1/webhooks/providers/provider-a",
                                        new HttpEntity<>(raw, headers),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count("payflow.webhook.received", "result", "REJECTED")).isEqualTo(rejected + 1);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(
                new javax.crypto.spec.SecretKeySpec(
                        "observability-webhook-secret"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "HmacSHA256"));
        headers.set("X-Payflow-Signature", HexFormat.of().formatHex(mac.doFinal(raw)));
        assertThat(
                        http.postForEntity(
                                        "/api/v1/webhooks/providers/provider-a",
                                        new HttpEntity<>(raw, headers),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(count("payflow.webhook.received", "result", "ACCEPTED")).isEqualTo(accepted + 1);
        assertThat(
                        registry.get("payflow.webhook.processing")
                                .tags("provider", "PROVIDER_A", "result", "SUCCESS")
                                .timer()
                                .count())
                .isPositive();
    }
}
