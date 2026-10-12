package com.payflow.payment.observability;

import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.provider.PaymentProviderType;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class PayflowMetrics {
    public enum Domain {
        PAYMENT,
        REFUND
    }

    public enum Outcome {
        SUCCESS,
        DECLINED,
        TECHNICAL_FAILURE,
        TIMEOUT,
        CIRCUIT_OPEN,
        UNKNOWN
    }

    public enum Operation {
        PAYMENT,
        REFUND,
        STATUS_QUERY
    }

    private static final Logger log = LoggerFactory.getLogger(PayflowMetrics.class);
    private final MeterRegistry registry;

    public PayflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // Process-local telemetry is best effort, never the authoritative financial ledger.
    private void increment(String name, String... tags) {
        try {
            registry.counter(name, tags).increment();
        } catch (RuntimeException failure) {
            log.warn("Metric recording unavailable name={}", name);
        }
    }

    public void afterCommit(Runnable recording) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            recording.run();
                        }
                    });
        } else recording.run();
    }

    public void paymentEvent(String type, PaymentMethod method) {
        String action =
                switch (type) {
                    case "PAYMENT_CREATED" -> "created";
                    case "PAYMENT_SUCCEEDED" -> "completed";
                    case "PAYMENT_FAILED" -> "failed";
                    default -> null;
                };
        if (action != null)
            afterCommit(
                    () -> increment("payflow.payment." + action, "paymentMethod", method.name()));
    }

    public void refundEvent(String type) {
        String action =
                switch (type) {
                    case "REFUND_CREATED" -> "created";
                    case "REFUND_SUCCEEDED" -> "completed";
                    case "REFUND_FAILED" -> "failed";
                    case "REFUND_PENDING_CONFIRMATION" -> "pending.confirmation";
                    default -> null;
                };
        if (action != null) afterCommit(() -> increment("payflow.refund." + action));
    }

    public void paymentPending() {
        afterCommit(() -> increment("payflow.payment.pending.confirmation"));
    }

    public void provider(PaymentProviderType provider, Operation operation, Outcome outcome) {
        String[] tags = {
            "provider", provider.name(), "operation", operation.name(), "outcome", outcome.name()
        };
        increment("payflow.provider.requests", tags);
        if (outcome != Outcome.SUCCESS) increment("payflow.provider.failures", tags);
    }

    public void outboxPublished() {
        afterCommit(() -> increment("payflow.outbox.published"));
    }

    public void outboxFailed() {
        afterCommit(() -> increment("payflow.outbox.failed"));
    }

    public void webhook(boolean accepted) {
        increment("payflow.webhook.received", "result", accepted ? "ACCEPTED" : "REJECTED");
    }

    public void reconciliationAttempt(Domain domain) {
        afterCommit(() -> increment("payflow.reconciliation.attempts", "domain", domain.name()));
    }

    public void reconciliationResolution(Domain domain, boolean success) {
        afterCommit(
                () ->
                        increment(
                                "payflow.reconciliation.resolutions",
                                "domain",
                                domain.name(),
                                "result",
                                success ? "SUCCESS" : "FAILED"));
    }

    public void reconciliationFailure(Domain domain) {
        afterCommit(() -> increment("payflow.reconciliation.failures", "domain", domain.name()));
    }

    public void cache(String result) {
        if (!java.util.Set.of("HIT", "MISS", "FALLBACK", "EVICTION_FAILURE", "WRITE_FAILURE")
                .contains(result)) throw new IllegalArgumentException("Unbounded cache result");
        increment("payflow.cache.requests", "result", result);
    }

    public void rate(String result) {
        if (!java.util.Set.of("ALLOWED", "REJECTED", "COORDINATION_FAILURE").contains(result))
            throw new IllegalArgumentException("Unbounded limiter result");
        increment("payflow.rate.limit.requests", "result", result);
    }
}
