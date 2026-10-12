package com.payflow.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ReconciliationProperties.class, WebhookProperties.class})
public class ReconciliationConfig {
    public ReconciliationConfig(ReconciliationProperties p, WebhookProperties w) {
        if (p.getInitialDelay().isNegative()
                || p.getBackoff().toMillis() < 1
                || p.getMaxBackoff().compareTo(p.getBackoff()) < 0
                || p.getLease().toMillis() < 1)
            throw new IllegalArgumentException("Invalid reconciliation delays/lease");
        if (w.isMockDeliveryEnabled() && (w.getASecret().isBlank() || w.getBSecret().isBlank()))
            throw new IllegalArgumentException(
                    "Mock webhook delivery requires both provider webhook secrets");
    }
}
