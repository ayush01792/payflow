package com.payflow.payment.outbox;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.OutboxStatus;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.repository.OutboxEventRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payflow.providers.enabled=false", "payflow.outbox.poll-interval-ms=100", "payflow.outbox.enabled=true",
                "spring.kafka.listener.auto-startup=true"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxSchedulingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    @Container
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "payflow-scheduled-outbox-test");
    }

    private final TestRestTemplate http;
    private final PaymentRepository payments;
    private final OutboxEventRepository outbox;
    private final ProcessedKafkaEventRepository processed;

    @Autowired
    OutboxSchedulingIntegrationTest(TestRestTemplate http, PaymentRepository payments,
                                    OutboxEventRepository outbox, ProcessedKafkaEventRepository processed) {
        this.http = http;
        this.payments = payments;
        this.outbox = outbox;
        this.processed = processed;
    }

    @Test
    void scheduledPublisherDeliversBothLifecycleEventsWithoutManualTrigger() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> request = Map.of("merchantId", "merchant-scheduled", "userId", "user-test",
                "amount", "12.50", "currency", "USD", "paymentMethod", "CARD");
        var response = http.postForEntity("/api/v1/payments", new HttpEntity<>(request, headers), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID paymentId = response.getBody().id();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(payments.findById(paymentId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(outbox.findAll()).hasSize(2).allMatch(row -> row.getStatus() == OutboxStatus.PUBLISHED
                    && row.getPublishedAt() != null && row.getAggregateId().equals(paymentId));
            assertThat(processed.count()).isEqualTo(2);
        });
        assertThat(http.getForEntity("/api/v1/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
