package com.payflow.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

public abstract class KafkaIntegrationSupport {

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        KAFKA.start(); // shared broker; Testcontainers Ryuk removes it at JVM exit
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("payflow.outbox.enabled", () -> false);
        registry.add("payflow.providers.enabled", () -> false);
        registry.add("spring.kafka.consumer.group-id", () -> "payflow-isolated-api-tests");
    }
}
