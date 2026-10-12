package com.payflow.payment.config;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties("payflow.reconciliation")
public class ReconciliationProperties {
    private boolean enabled = true;

    @Min(1)
    @Max(500)
    private int batchSize = 50;

    @Min(1)
    @Max(100)
    private int maxAttempts = 10;

    @NotNull private Duration initialDelay = Duration.ofSeconds(10);
    @NotNull private Duration backoff = Duration.ofSeconds(10);
    @NotNull private Duration maxBackoff = Duration.ofMinutes(5);
    @NotNull private Duration lease = Duration.ofSeconds(30);

    public long delayMillis(int attempt) {
        return Math.min(
                maxBackoff.toMillis(),
                Math.multiplyExact(
                        backoff.toMillis(), 1L << Math.min(20, Math.max(0, attempt - 1))));
    }
}
