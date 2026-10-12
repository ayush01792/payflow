package com.payflow.payment.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.payflow.payment.config.WebhookProperties;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.mock.*;
import com.payflow.payment.provider.PaymentProviderType;
import com.payflow.payment.repository.*;
import com.payflow.payment.service.RefundService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.*;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payflow.outbox.poll-interval-ms=50",
            "payflow.reconciliation.poll-interval-ms=50",
            "payflow.reconciliation.initial-delay=1s",
            "payflow.reconciliation.backoff=1s",
            "payflow.webhooks.a-secret=refund-scheduled-a",
            "payflow.webhooks.b-secret=refund-scheduled-b",
            "payflow.webhooks.mock-delivery-enabled=true",
            "payflow.webhooks.mock-delay-ms=700",
            "payflow.webhooks.mock-poll-interval-ms=50",
            "payflow.providers.read-timeout=100ms",
            "resilience4j.retry.configs.providerDefault.wait-duration=10ms"
        })
@Testcontainers
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundSchedulingIntegrationTest {
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

    private final TestRestTemplate http;
    private final RefundService refunds;
    private final PaymentRepository payments;
    private final OutboxEventRepository outbox;
    private final MockProviderControl controls;
    private final WebhookProperties properties;
    private final JdbcTemplate jdbc;

    RefundSchedulingIntegrationTest(
            TestRestTemplate http,
            RefundService refunds,
            PaymentRepository payments,
            OutboxEventRepository outbox,
            MockProviderControl controls,
            WebhookProperties properties,
            JdbcTemplate jdbc) {
        this.http = http;
        this.refunds = refunds;
        this.payments = payments;
        this.outbox = outbox;
        this.controls = controls;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void paymentAndRefundAutomaticallyRunKafkaOutboxAndRecovery(boolean webhook) {
        properties.setMockDeliveryEnabled(false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var payment =
                http.postForEntity(
                                "/api/v1/payments",
                                new HttpEntity<>(
                                        Map.of(
                                                "merchantId",
                                                "refund-scheduled-" + UUID.randomUUID(),
                                                "userId",
                                                "user",
                                                "amount",
                                                1000,
                                                "currency",
                                                "USD",
                                                "paymentMethod",
                                                "UPI"),
                                        headers),
                                PaymentResponse.class)
                        .getBody();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () ->
                                assertThat(
                                                payments.findById(payment.id())
                                                        .orElseThrow()
                                                        .getStatus())
                                        .isEqualTo(PaymentStatus.SUCCESS));
        properties.setMockDeliveryEnabled(webhook);
        controls.configureRefund(
                PaymentProviderType.PROVIDER_A, new MockBehavior(MockScenario.TIMEOUT, 0, 350));
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.postForEntity(
                        "/api/v1/payments/" + payment.id() + "/refunds",
                        new HttpEntity<>(
                                Map.of(
                                        "amount",
                                        1000,
                                        "merchantRefundReference",
                                        "refund-" + UUID.randomUUID()),
                                headers),
                        RefundResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status()).isEqualTo(RefundStatus.CREATED);
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(refunds.get(id).status())
                                        .isEqualTo(RefundStatus.PENDING_CONFIRMATION));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> assertThat(refunds.get(id).status()).isEqualTo(RefundStatus.SUCCESS));
        assertThat(payments.findById(payment.id()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () ->
                                assertThat(
                                                outbox.findAll().stream()
                                                        .filter(
                                                                row ->
                                                                        row.getAggregateId()
                                                                                .equals(id))
                                                        .toList())
                                        .hasSize(4)
                                        .allMatch(
                                                row -> row.getStatus() == OutboxStatus.PUBLISHED));
        assertThat(
                        http.getForEntity("/api/v1/refunds/" + id, RefundResponse.class)
                                .getBody()
                                .status())
                .isEqualTo(RefundStatus.SUCCESS);
        if (webhook)
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM provider_webhook_events WHERE"
                                            + " refund_id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(1);
        else
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM refund_reconciliation_attempts WHERE"
                                            + " refund_id=?",
                                    Long.class,
                                    id))
                    .isEqualTo(1);
    }
}
