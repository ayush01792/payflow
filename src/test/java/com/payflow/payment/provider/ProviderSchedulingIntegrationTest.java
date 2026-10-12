package com.payflow.payment.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.OutboxStatus;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.entity.ProviderAttemptStatus;
import com.payflow.payment.repository.OutboxEventRepository;
import com.payflow.payment.repository.PaymentProviderAttemptRepository;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.ProcessedKafkaEventRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payflow.reconciliation.enabled=false",
            "payflow.outbox.poll-interval-ms=100",
            "payflow.providers.enabled=true"
        })
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderSchedulingIntegrationTest {
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
        r.add("spring.kafka.consumer.group-id", () -> "payflow-provider-scheduled");
    }

    private final TestRestTemplate http;
    private final PaymentRepository payments;
    private final PaymentProviderAttemptRepository attempts;
    private final OutboxEventRepository outbox;
    private final ProcessedKafkaEventRepository processed;

    @Autowired
    ProviderSchedulingIntegrationTest(
            TestRestTemplate http,
            PaymentRepository payments,
            PaymentProviderAttemptRepository attempts,
            OutboxEventRepository outbox,
            ProcessedKafkaEventRepository processed) {
        this.http = http;
        this.payments = payments;
        this.attempts = attempts;
        this.outbox = outbox;
        this.processed = processed;
    }

    @Test
    void defaultScheduledLifecycleReachesSuccessThroughRealHttpAndKafka() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var request =
                Map.of(
                        "merchantId",
                        "merchant-auto",
                        "userId",
                        "user-test",
                        "amount",
                        "12.50",
                        "currency",
                        "USD",
                        "paymentMethod",
                        "UPI");
        var response =
                http.postForEntity(
                        "/api/v1/payments",
                        new HttpEntity<>(request, headers),
                        PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.CREATED);
        UUID id = response.getBody().id();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> {
                            assertThat(payments.findById(id).orElseThrow().getStatus())
                                    .isEqualTo(PaymentStatus.SUCCESS);
                            assertThat(attempts.findAll())
                                    .hasSize(1)
                                    .allMatch(
                                            row ->
                                                    row.getStatus()
                                                            == ProviderAttemptStatus.SUCCEEDED);
                            assertThat(outbox.findAll())
                                    .hasSize(3)
                                    .allMatch(row -> row.getStatus() == OutboxStatus.PUBLISHED);
                            assertThat(processed.count()).isEqualTo(3);
                        });
        assertThat(http.getForObject("/api/v1/payments/" + id, PaymentResponse.class).status())
                .isEqualTo(PaymentStatus.SUCCESS);
    }
}
