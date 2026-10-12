package com.payflow.payment.observability;

import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Component("kafkaHealthIndicator")
@ConditionalOnProperty(
        name = "management.health.kafka.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class KafkaHealthIndicator implements HealthIndicator {
    private final AdminClient admin;
    private volatile Health cached = Health.unknown().build();
    private volatile long nextCheck;

    public KafkaHealthIndicator(KafkaAdmin kafka) {
        var config = new HashMap<String, Object>(kafka.getConfigurationProperties());
        config.put("request.timeout.ms", 1000);
        config.put("default.api.timeout.ms", 1000);
        admin = AdminClient.create(config);
    }

    @Override
    public synchronized Health health() {
        long now = System.nanoTime();
        if (now < nextCheck) return cached;
        try {
            cached =
                    admin.describeCluster().nodes().get(1200, TimeUnit.MILLISECONDS).isEmpty()
                            ? Health.down().build()
                            : Health.up().build();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            cached = Health.down().build();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException
                | RuntimeException failure) {
            cached = Health.down().build();
        }
        nextCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        return cached;
    }

    @PreDestroy
    void close() {
        admin.close(java.time.Duration.ofSeconds(1));
    }
}
