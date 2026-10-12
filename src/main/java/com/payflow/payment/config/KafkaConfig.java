package com.payflow.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.event.PaymentEvent;
import com.payflow.payment.event.PaymentEventTopics;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.exception.InvalidPaymentTransitionException;
import com.payflow.payment.provider.ProviderWorkInProgressException;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;
import java.util.Map;

@Configuration
public class KafkaConfig {

    private final int partitions;
    private final int replicas;
    private final int minIsr;

    public KafkaConfig(
            @Value("${payflow.kafka.partitions}") int partitions,
            @Value("${payflow.kafka.replication-factor}") int replicas,
            @Value("${payflow.kafka.min-in-sync-replicas}") int minIsr) {
        if (partitions < 1 || replicas < 1 || minIsr < 1 || minIsr > replicas) {
            throw new IllegalArgumentException(
                    "Invalid Kafka topic partition/replication configuration");
        }
        this.partitions = partitions;
        this.replicas = replicas;
        this.minIsr = minIsr;
    }

    @Bean
    public NewTopic paymentEventsTopic() {
        return topic(PaymentEventTopics.EVENTS);
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return topic(PaymentEventTopics.DLT);
    }

    @Bean
    public NewTopic refundEventsTopic() {
        return topic(com.payflow.payment.event.RefundEventTopics.EVENTS);
    }

    @Bean
    public NewTopic refundEventsDeadLetterTopic() {
        return topic(com.payflow.payment.event.RefundEventTopics.DLT);
    }

    @Bean
    public org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<
                    String, com.payflow.payment.event.RefundEvent>
            refundKafkaListenerContainerFactory(
                    KafkaProperties properties,
                    SslBundles sslBundles,
                    DefaultErrorHandler paymentKafkaErrorHandler,
                    @Value("${spring.kafka.listener.auto-startup:true}") boolean startup,
                    @Value("${spring.kafka.listener.concurrency:3}") int concurrency) {
        var config = properties.buildConsumerProperties(sslBundles);
        config.put(
                "spring.json.value.default.type",
                com.payflow.payment.event.RefundEvent.class.getName());
        var factory =
                new org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory<
                        String, com.payflow.payment.event.RefundEvent>();
        factory.setConsumerFactory(
                new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(config));
        factory.setCommonErrorHandler(paymentKafkaErrorHandler);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setConcurrency(concurrency);
        factory.setAutoStartup(startup);
        factory.getContainerProperties()
                .setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        return factory;
    }

    private NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicas)
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(minIsr))
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> paymentProducerFactory(
            KafkaProperties properties, SslBundles sslBundles, ObjectMapper objectMapper) {
        Map<Class<?>, Serializer<?>> serializers =
                Map.of(
                        PaymentEvent.class,
                        new JsonSerializer<PaymentEvent>(objectMapper).noTypeInfo(),
                        com.payflow.payment.event.RefundEvent.class,
                        new JsonSerializer<com.payflow.payment.event.RefundEvent>(objectMapper)
                                .noTypeInfo(),
                        byte[].class,
                        new ByteArraySerializer());
        // Raw bytes must remain raw when malformed JSON is recovered to the DLT.
        return new DefaultKafkaProducerFactory<>(
                properties.buildProducerProperties(sslBundles),
                new StringSerializer(),
                new DelegatingByTypeSerializer(serializers));
    }

    @Bean
    public KafkaTemplate<String, Object> paymentKafkaTemplate(
            ProducerFactory<String, Object> factory) {
        var template = new KafkaTemplate<String, Object>(factory);
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public DefaultErrorHandler paymentKafkaErrorHandler(
            KafkaTemplate<String, Object> template,
            @Value("${payflow.kafka.retry-backoff-ms}") long backoffMillis,
            @Value("${payflow.kafka.max-retries}") long maxRetries,
            ProviderProperties providers) {
        if (backoffMillis < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("Kafka retry settings must be nonnegative");
        }
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, exception) ->
                                new TopicPartition(
                                        record.topic()
                                                        .equals(
                                                                com.payflow.payment.event
                                                                        .RefundEventTopics.EVENTS)
                                                ? com.payflow.payment.event.RefundEventTopics.DLT
                                                : PaymentEventTopics.DLT,
                                        record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(35));
        DefaultErrorHandler handler =
                new DefaultErrorHandler(recoverer, new FixedBackOff(backoffMillis, maxRetries));
        handler.addNotRetryableExceptions(
                InvalidPaymentEventException.class, InvalidPaymentTransitionException.class);
        if (providers.getBusyRetryAttempts() < 1)
            throw new IllegalArgumentException("Provider busy retries must be positive");
        handler.setBackOffFunction(
                (record, failure) -> {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        if (cause instanceof ProviderWorkInProgressException) {
                            // Bounded retries span a full lease, so ordinary crashed-owner recovery
                            // is not sent to DLT too early.
                            long delay =
                                    providers.getOrchestrationLease().toMillis()
                                                    / providers.getBusyRetryAttempts()
                                            + 1000;
                            return new FixedBackOff(delay, providers.getBusyRetryAttempts());
                        }
                    }
                    return null; // existing retry/DLT policy for other consumer failures
                });
        return handler;
    }
}
