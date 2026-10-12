package com.payflow.payment.event;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.OutboxEvent;
import com.payflow.payment.entity.OutboxStatus;
import com.payflow.payment.repository.OutboxEventRepository;
import com.payflow.payment.outbox.OutboxPublisher;
import com.payflow.payment.outbox.OutboxStore;
import com.payflow.payment.outbox.OutboxClaim;
import com.payflow.payment.service.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.dao.DataAccessResourceFailureException;
import com.fasterxml.jackson.core.JsonProcessingException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import java.util.concurrent.ConcurrentHashMap;
import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.PaymentIdempotencyRepository;
import com.payflow.payment.repository.ProcessedKafkaEventRepository;
import com.payflow.payment.service.PaymentLifecycleService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        properties = {"payflow.providers.enabled=false", "spring.kafka.listener.auto-startup=false", "payflow.kafka.retry-backoff-ms=100",
                "payflow.outbox.enabled=false", "payflow.outbox.retry-delay-ms=1000",
                "payflow.outbox.max-attempts=3", "payflow.outbox.batch-size=3"})
@Testcontainers
@MockitoSpyBean(types = {PaymentLifecycleService.class, PaymentEventPublisher.class, OutboxStore.class, ObjectMapper.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentKafkaIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @Container static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379);
    @Container static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.data.redis.password", () -> "");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.group-id", () -> "payflow-lifecycle-integration");
    }
    private final PaymentRepository payments;
    private final PaymentIdempotencyRepository idempotency;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentLifecycleService lifecycle;
    private final KafkaTemplate<String, Object> producer;
    private final KafkaListenerEndpointRegistry listeners;
    private final StringRedisTemplate redisTemplate;
    private final TestRestTemplate http;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    private final OutboxEventRepository outbox;
    private final OutboxService outboxService;
    private final OutboxPublisher publisher;
    private final OutboxStore store;
    private final PaymentEventPublisher transport;
    private final JdbcTemplate jdbc;


    @Autowired
    PaymentKafkaIntegrationTest(PaymentRepository payments,
            PaymentIdempotencyRepository idempotency,
            ProcessedKafkaEventRepository processed,
            PaymentLifecycleService lifecycle,
            KafkaTemplate<String, Object> producer,
            KafkaListenerEndpointRegistry listeners,
            StringRedisTemplate redisTemplate,
            TestRestTemplate http,
            ObjectMapper mapper,
            PlatformTransactionManager transactions,
            OutboxEventRepository outbox, OutboxService outboxService,
            OutboxPublisher publisher, OutboxStore store, PaymentEventPublisher transport, JdbcTemplate jdbc) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.processed = processed;
        this.lifecycle = lifecycle;
        this.producer = producer;
        this.listeners = listeners;
        this.redisTemplate = redisTemplate;
        this.http = http;
        this.mapper = mapper;
        this.transactions = transactions;
        this.outbox = outbox;
        this.outboxService = outboxService;
        this.publisher = publisher;
        this.store = store;
        this.transport = transport;
        this.jdbc = jdbc;
    }

    @BeforeEach void clean() {
        listeners.getListenerContainer("paymentLifecycleListener").stop();
        outbox.deleteAll(); processed.deleteAll(); idempotency.deleteAll(); payments.deleteAll();
        clearInvocations(lifecycle);
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> { c.serverCommands().flushDb(); return null; });
    }
    @AfterEach void stop() { listeners.getListenerContainer("paymentLifecycleListener").stop(); }
    void start() { listeners.getListenerContainer("paymentLifecycleListener").start(); }
    Payment payment(PaymentStatus status) {
        return payments.saveAndFlush(new Payment("merchant-" + UUID.randomUUID(), "user-test", new BigDecimal("12.5000"),
                Currency.getInstance("USD"), PaymentMethod.CARD, status));
    }
    PaymentEvent created(Payment p) {
        return PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, new PaymentResponse(p.getId(), p.getMerchantId(),
                p.getUserId(), p.getAmount(), "USD", p.getPaymentMethod(), PaymentStatus.CREATED, p.getCreatedAt(), p.getUpdatedAt()));
    }
    void send(PaymentEvent e) throws Exception { producer.send(PaymentEventTopics.EVENTS, e.paymentId().toString(), e).get(15, TimeUnit.SECONDS); }
    List<ConsumerRecord<String,String>> records(String topic, UUID id, int minimum) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String,String>> found = new ArrayList<>();
        try (KafkaConsumer<String,String> c = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            c.assign(c.partitionsFor(topic).stream().map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition())).toList());
            c.seekToBeginning(c.assignment());
            long end = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            do { c.poll(Duration.ofMillis(300)).forEach(r -> { if (id.toString().equals(r.key())) found.add(r); }); }
            while (found.size() < minimum && System.nanoTime() < end);
        }
        return found;
    }
    @Test void creationReplayLifecycleAndCacheEviction() throws Exception {
        var health = http.getForEntity("/api/v1/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(health.getBody()).path("status").asText()).isEqualTo("UP");
        HttpHeaders headers = new HttpHeaders(); headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String,Object> body = Map.of("merchantId", "merchant-" + UUID.randomUUID(), "userId", "user-test",
                "amount", "12.50", "currency", "USD", "paymentMethod", "CARD");
        var request = new HttpEntity<>(body, headers);
        var first = http.postForEntity("/api/v1/payments", request, PaymentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = first.getBody().id();
        assertThat(http.getForObject("/api/v1/payments/" + id, PaymentResponse.class).status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(redisTemplate.hasKey("payflow:payment:" + id)).isTrue();
        assertThat(http.postForEntity("/api/v1/payments", request, PaymentResponse.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(payments.count()).isEqualTo(1);
        assertThat(idempotency.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.PENDING);
        body = new java.util.HashMap<>(body); body.put("amount", "20.00");
        assertThat(http.postForEntity("/api/v1/payments", new HttpEntity<>(body, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outbox.findAll().getFirst().getPublishedAt()).isNotNull();
        var before = records(PaymentEventTopics.EVENTS, id, 1);
        assertThat(before).hasSize(1);
        PaymentEvent original = mapper.readValue(before.getFirst().value(), PaymentEvent.class);
        assertThat(original.eventId()).isNotEqualTo(id);
        assertThat(original.eventVersion()).isEqualTo(1);
        assertThat(original.eventType()).isEqualTo(PaymentEventType.PAYMENT_CREATED);
        assertThat(original.occurredAt()).isNotNull();
        start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(payments.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(processed.existsById(original.eventId())).isTrue();
            assertThat(redisTemplate.hasKey("payflow:payment:" + id)).isFalse();
        });
        assertThat(outbox.count()).isEqualTo(2);
        var processingRow = outbox.findAll().stream().filter(row -> row.getEventType().equals("PAYMENT_PROCESSING")).findFirst().orElseThrow();
        assertThat(processingRow.getStatus()).isEqualTo(OutboxStatus.PENDING);
        publisher.publishPending();
        var after = records(PaymentEventTopics.EVENTS, id, 2);
        assertThat(after).hasSize(2);
        var next = mapper.readValue(after.get(1).value(), PaymentEvent.class);
        assertThat(next.eventType()).isEqualTo(PaymentEventType.PAYMENT_PROCESSING);
        assertThat(next.eventId()).isNotEqualTo(original.eventId());
        assertThat(after.get(1).partition()).isEqualTo(before.getFirst().partition());
        assertThat(http.getForObject("/api/v1/payments/" + id, PaymentResponse.class).status()).isEqualTo(PaymentStatus.PROCESSING);
        send(original); send(original);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(processed.existsById(next.eventId())).isTrue();
            verify(lifecycle, times(3)).handle(original);
        });
        var duplicateRecords = records(PaymentEventTopics.EVENTS, id, 4);
        assertThat(duplicateRecords.stream().filter(r -> r.value().contains("PAYMENT_PROCESSING")).count()).isEqualTo(1);
    }
    @Test void concurrentDuplicateClaimsPublishOneTransition() throws Exception {
        Payment p = payment(PaymentStatus.CREATED); PaymentEvent e = created(p);
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            CountDownLatch gate = new CountDownLatch(1); List<Future<?>> tasks = new ArrayList<>();
            for (int i=0;i<6;i++) tasks.add(pool.submit(() -> { try { gate.await(); } catch (InterruptedException x) { Thread.currentThread().interrupt(); throw new RuntimeException(x); } lifecycle.handle(e); }));
            gate.countDown(); for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertThat(processed.count()).isEqualTo(1);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        lifecycle.handle(created(p)); // different event ID, already PROCESSING
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findAll().getFirst().getEventType()).isEqualTo("PAYMENT_PROCESSING");
        publisher.publishPending();
        assertThat(records(PaymentEventTopics.EVENTS, p.getId(), 1)).hasSize(1);
    }
    @Test void missingPaymentExhaustsRetriesAndGoesToDeadLetter() throws Exception {
        Payment p = payment(PaymentStatus.CREATED); PaymentEvent e = created(p); payments.deleteById(p.getId());
        send(e); start();
        assertThat(records(PaymentEventTopics.DLT, p.getId(), 1)).hasSize(1);
        assertThat(processed.existsById(e.eventId())).isFalse();
        verify(lifecycle, times(3)).handle(e);
    }
    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"SUCCESS", "FAILED"})
    void terminalPaymentCannotMoveBackwards(PaymentStatus status) throws Exception {
        Payment p = payment(status); PaymentEvent e = created(p); send(e); start();
        assertThat(records(PaymentEventTopics.DLT, p.getId(), 1)).hasSize(1);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(processed.existsById(e.eventId())).isFalse();
    }
    @Test void malformedJsonIsPreservedInDeadLetterTopic() throws Exception {
        UUID id = UUID.randomUUID(); String malformed = "{invalid json";
        producer.send(PaymentEventTopics.EVENTS, id.toString(), malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).get(15, TimeUnit.SECONDS);
        start(); var dead = records(PaymentEventTopics.DLT, id, 1);
        assertThat(dead).hasSize(1); assertThat(dead.getFirst().value()).isEqualTo(malformed);
        assertThat(dead.getFirst().headers().lastHeader("kafka_dlt-original-topic")).isNotNull();
    }
    @Test void rolledBackTransactionDoesNotPublishEvent() {
        Payment p = payment(PaymentStatus.CREATED);
        PaymentEvent e = created(p);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            outboxService.savePaymentEvent(e);
            tx.setRollbackOnly();
        });
        assertThat(outbox.count()).isZero();
        assertThat(records(PaymentEventTopics.EVENTS, p.getId(), 0)).isEmpty();
    }
    @Test void invalidVersionIsRejectedWithoutMarker() throws Exception {
        Payment p = payment(PaymentStatus.CREATED); PaymentEvent e = created(p);
        PaymentEvent invalid = new PaymentEvent(e.eventId(), e.eventType(), 2, e.paymentId(), e.merchantId(), e.userId(), e.amount(), e.currency(), e.paymentMethod(), e.status(), e.occurredAt());
        send(invalid); start(); assertThat(records(PaymentEventTopics.DLT, p.getId(), 1)).hasSize(1);
        assertThat(processed.existsById(e.eventId())).isFalse();
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    PaymentResponse postPayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> body = Map.of("merchantId", "merchant-" + UUID.randomUUID(), "userId", "user-test",
                "amount", "12.50", "currency", "USD", "paymentMethod", "CARD");
        var response = http.postForEntity("/api/v1/payments", new HttpEntity<>(body, headers), PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    void makeRetryDue(UUID id) {
        jdbc.update("UPDATE outbox_events SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
    }

    void expireLease(UUID id) {
        jdbc.update("UPDATE outbox_events SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", id);
    }

    @Test void serializationFailureRollsBackPaymentAndIdempotency() throws Exception {
        doThrow(new JsonProcessingException("controlled serialization failure") {}).when(mapper).writeValueAsString(isA(PaymentEvent.class));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var body = Map.of("merchantId", "merchant-test", "userId", "user-test", "amount", "12.50", "currency", "USD", "paymentMethod", "CARD");
        var response = http.postForEntity("/api/v1/payments", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(payments.count()).isZero();
        assertThat(idempotency.count()).isZero();
        assertThat(outbox.count()).isZero();
        verify(transport, never()).publish(any(), anyString());
    }

    @Test void serializationFailureRollsBackLifecycleMarkerAndPreservesCache() throws Exception {
        Payment p = payment(PaymentStatus.CREATED);
        http.getForObject("/api/v1/payments/" + p.getId(), PaymentResponse.class);
        PaymentEvent event = created(p);
        doThrow(new JsonProcessingException("controlled serialization failure") {}).when(mapper).writeValueAsString(isA(PaymentEvent.class));
        assertThatThrownBy(() -> lifecycle.handle(event)).isInstanceOf(IllegalStateException.class);
        assertThat(payments.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(processed.existsById(event.eventId())).isFalse();
        assertThat(outbox.count()).isZero();
        assertThat(redisTemplate.hasKey("payflow:payment:" + p.getId())).isTrue();
    }

    @Test void outboxCreationRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> outboxService.savePaymentEvent(created(payment(PaymentStatus.CREATED))))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void kafkaFailureRetainsCommittedIntentAndRecoversWithSameEventId() throws Exception {
        doThrow(new org.springframework.kafka.KafkaException("secret broker credentials must not be stored"))
                .when(transport).publish(any(), anyString());
        PaymentResponse payment = postPayment();
        OutboxEvent original = outbox.findAll().getFirst();
        String immutablePayload = original.getPayload();
        verify(transport, never()).publish(any(), anyString());
        publisher.publishPending();
        OutboxEvent failed = outbox.findById(original.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("Kafka publish failed: KafkaException");
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(payments.existsById(payment.id())).isTrue();
        assertThat(idempotency.count()).isEqualTo(1);
        doCallRealMethod().when(transport).publish(any(), anyString());
        makeRetryDue(original.getId());
        publisher.publishPending();
        OutboxEvent sent = outbox.findById(original.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(sent.getAttemptCount()).isEqualTo(2);
        assertThat(sent.getPayload()).isEqualTo(immutablePayload);
        var record = records(PaymentEventTopics.EVENTS, payment.id(), 1).getFirst();
        assertThat(mapper.readTree(record.value()).path("eventId").asText()).isEqualTo(original.getId().toString());
        assertThat(record.key()).isEqualTo(payment.id().toString());
    }

    @Test void permanentlyFailingRowsAreRetainedAfterBoundedAttempts() {
        postPayment();
        UUID id = outbox.findAll().getFirst().getId();
        doThrow(new org.springframework.kafka.KafkaException("test")).when(transport).publish(any(), anyString());
        for (int attempt = 1; attempt <= 3; attempt++) {
            makeRetryDue(id);
            assertThat(publisher.publishPending()).isEqualTo(1);
            assertThat(outbox.findById(id).orElseThrow().getAttemptCount()).isEqualTo(attempt);
        }
        assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outbox.findById(id).orElseThrow().getPublishedAt()).isNull();
        assertThat(publisher.publishPending()).isZero();
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test void expiredLeaseCanBeReclaimedAndOldOwnerCannotModifyIt() throws Exception {
        PaymentResponse p = postPayment();
        OutboxClaim oldClaim = store.claimNext().orElseThrow();
        assertThat(store.claimNext()).isEmpty();
        expireLease(oldClaim.id());
        OutboxClaim newClaim = store.claimNext().orElseThrow();
        assertThat(newClaim.id()).isEqualTo(oldClaim.id());
        assertThat(newClaim.token()).isNotEqualTo(oldClaim.token());
        assertThat(newClaim.attempt()).isEqualTo(2);
        assertThat(store.markPublished(oldClaim)).isFalse();
        store.recordFailure(oldClaim, "stale failure");
        assertThat(outbox.findById(oldClaim.id()).orElseThrow().getClaimToken()).isEqualTo(newClaim.token());
        transport.publish(p.id(), newClaim.payload()).get(15, TimeUnit.SECONDS);
        assertThat(store.markPublished(newClaim)).isTrue();
    }

    @Test void acknowledgedSendWithFailedDatabaseMarkReplaysSafely() throws Exception {
        PaymentResponse p = postPayment();
        UUID id = outbox.findAll().getFirst().getId();
        doThrow(new DataAccessResourceFailureException("controlled DB outage"))
                .doCallRealMethod().when(store).markPublished(any());
        publisher.publishPending();
        assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.CLAIMED);
        assertThat(records(PaymentEventTopics.EVENTS, p.id(), 1)).hasSize(1);
        expireLease(id);
        publisher.publishPending();
        assertThat(outbox.findById(id).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        var duplicates = records(PaymentEventTopics.EVENTS, p.id(), 2);
        assertThat(duplicates).hasSize(2);
        assertThat(mapper.readTree(duplicates.get(0).value()).path("eventId").asText()).isEqualTo(id.toString());
        assertThat(duplicates.get(1).value()).isEqualTo(duplicates.get(0).value());
        start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(processed.existsById(id)).isTrue();
            assertThat(payments.findById(p.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(outbox.count()).isEqualTo(2);
            verify(lifecycle, times(2)).handle(mapper.readValue(duplicates.getFirst().value(), PaymentEvent.class));
        });
        var processingRow = outbox.findAll().stream().filter(row -> row.getEventType().equals("PAYMENT_PROCESSING")).findFirst().orElseThrow();
        publisher.publishPending();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(processed.existsById(processingRow.getId())).isTrue());
        assertThat(outbox.count()).isEqualTo(2);
    }

    @Test void concurrentWorkersClaimDifferentRowsWithoutHoldingTransactionsDuringSend() throws Exception {
        for (int i = 0; i < 8; i++) postPayment();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Map<UUID, Integer> sends = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            UUID eventId = UUID.fromString(mapper.readTree((String) invocation.getArgument(1)).path("eventId").asText());
            sends.merge(eventId, 1, Integer::sum);
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timed out");
            return invocation.callRealMethod();
        }).when(transport).publish(any(), anyString());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(publisher::publishPending);
            Future<Integer> second = pool.submit(publisher::publishPending);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(outbox.findAll().stream().filter(row -> row.getStatus() == OutboxStatus.CLAIMED).count()).isEqualTo(2);
            } finally {
                release.countDown();
            }
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(3);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(3);
        }
        assertThat(publisher.publishPending()).isEqualTo(2);
        assertThat(sends).hasSize(8);
        assertThat(sends.values()).containsOnly(1);
        assertThat(outbox.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.PUBLISHED);
    }

    @Test void skipLockedAllowsOtherPaymentsToProgress() throws Exception {
        postPayment(); postPayment();
        List<OutboxEvent> rows = outbox.findAll();
        rows.sort(java.util.Comparator.comparing(OutboxEvent::getCreatedAt).thenComparing(OutboxEvent::getId));
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM outbox_events WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, rows.getFirst().getId()); statement.executeQuery().close();
            }
            OutboxClaim claim = store.claimNext().orElseThrow();
            assertThat(claim.id()).isEqualTo(rows.get(1).getId());
            connection.rollback();
        }
    }

    @Test void earlierUnpublishedEventBlocksLaterEventForSamePayment() {
        Payment p = payment(PaymentStatus.CREATED);
        PaymentEvent first = created(p);
        PaymentEvent next = PaymentEvent.forPayment(PaymentEventType.PAYMENT_PROCESSING,
                new PaymentResponse(p.getId(), p.getMerchantId(), p.getUserId(), p.getAmount(), "USD", p.getPaymentMethod(),
                        PaymentStatus.PROCESSING, p.getCreatedAt(), p.getUpdatedAt()));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            outboxService.savePaymentEvent(first);
            outboxService.savePaymentEvent(next);
        });
        OutboxClaim claim = store.claimNext().orElseThrow();
        assertThat(claim.id()).isEqualTo(first.eventId());
        assertThat(store.claimNext()).isEmpty();
        store.recordFailure(claim, "test failure");
        assertThat(store.claimNext()).isEmpty();
        makeRetryDue(claim.id());
        publisher.publishPending();
        assertThat(outbox.findById(next.eventId()).orElseThrow().getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }


    @Test void failedOutboxInsertRollsBackAllCreationRows() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Payment p = payment(PaymentStatus.CREATED);
            idempotency.saveAndFlush(new com.payflow.payment.entity.PaymentIdempotency(p.getMerchantId(), "test-key", "0".repeat(64), p.getId()));
            PaymentEvent valid = created(p);
            PaymentEvent unsupported = new PaymentEvent(valid.eventId(), valid.eventType(), 2, valid.paymentId(),
                    valid.merchantId(), valid.userId(), valid.amount(), valid.currency(), valid.paymentMethod(), valid.status(), valid.occurredAt());
            outboxService.savePaymentEvent(unsupported); // V4 rejects version 2 at the database boundary.
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(payments.count()).isZero();
        assertThat(idempotency.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test void expiredFinalAttemptBecomesFailedInsteadOfRemainingClaimedForever() {
        postPayment();
        OutboxClaim claim = store.claimNext().orElseThrow();
        jdbc.update("UPDATE outbox_events SET attempt_count = 3, lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", claim.id());
        assertThat(store.claimNext()).isEmpty();
        OutboxEvent failed = outbox.findById(claim.id()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getClaimToken()).isNull();
        assertThat(failed.getLastError()).isNotBlank();
    }

}
