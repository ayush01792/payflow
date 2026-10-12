package com.payflow.payment.outbox;

import com.payflow.payment.entity.OutboxStatus;
import com.payflow.payment.observability.PayflowMetrics;
import com.payflow.payment.repository.OutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class OutboxStore {
    private static final Logger log = LoggerFactory.getLogger(OutboxStore.class);
    private final OutboxEventRepository rows;
    private final int maxAttempts;
    private final Duration lease;
    private final Duration retryDelay;

    private final PayflowMetrics metrics;

    public OutboxStore(
            OutboxEventRepository rows,
            @Value("${payflow.outbox.max-attempts}") int maxAttempts,
            @Value("${payflow.outbox.lease-ms}") long leaseMillis,
            @Value("${payflow.outbox.retry-delay-ms}") long retryMillis,
            @Value("${payflow.outbox.send-timeout-ms}") long sendTimeoutMillis,
            PayflowMetrics metrics) {
        if (maxAttempts < 1
                || retryMillis < 1
                || sendTimeoutMillis < 1
                || leaseMillis <= sendTimeoutMillis + 5000) {
            throw new IllegalArgumentException("Invalid outbox retry/lease configuration");
        }
        this.rows = rows;
        this.maxAttempts = maxAttempts;
        this.lease = Duration.ofMillis(leaseMillis);
        this.retryDelay = Duration.ofMillis(retryMillis);
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxClaim> claimNext() {
        return rows.lockNextEligible()
                .flatMap(
                        row -> {
                            if (row.getAttemptCount() >= maxAttempts) {
                                row.failExhaustedLease();
                                log.error(
                                        "Outbox permanently failed outboxId={} eventId={}"
                                                + " paymentId={} eventType={} attempt={}",
                                        row.getId(),
                                        row.getId(),
                                        row.getAggregateId(),
                                        row.getEventType(),
                                        row.getAttemptCount());
                                return Optional.empty();
                            }
                            UUID token = UUID.randomUUID();
                            row.claim(token, rows.databaseNow().plus(lease));
                            log.debug(
                                    "Outbox claimed outboxId={} eventId={} paymentId={}"
                                            + " eventType={} attempt={}",
                                    row.getId(),
                                    row.getId(),
                                    row.getAggregateId(),
                                    row.getEventType(),
                                    row.getAttemptCount());
                            return Optional.of(
                                    new OutboxClaim(
                                            row.getId(),
                                            row.getAggregateId(),
                                            row.getEventType(),
                                            row.getPayload(),
                                            token,
                                            row.getAttemptCount()));
                        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(OutboxClaim claim) {
        boolean changed = rows.markPublished(claim.id(), claim.token()) == 1;
        if (changed) metrics.outboxPublished();
        return changed;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUncertainSend(OutboxClaim claim) {
        int changed =
                rows.recordUncertainSend(
                        claim.id(),
                        claim.token(),
                        "Kafka acknowledgment timed out; recovering after lease expiry");
        if (changed == 1) metrics.outboxFailed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OutboxClaim claim, String safeError) {
        OutboxStatus status =
                claim.attempt() >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
        String boundedError = safeError.substring(0, Math.min(500, safeError.length()));
        int changed =
                rows.recordFailure(
                        claim.id(),
                        claim.token(),
                        status.name(),
                        boundedError,
                        retryDelay.toMillis());
        if (changed == 1) {
            metrics.outboxFailed();
            log.warn(
                    "Outbox {} outboxId={} eventId={} paymentId={} eventType={} attempt={}"
                            + " error={}",
                    status == OutboxStatus.FAILED ? "permanently failed" : "retry scheduled",
                    claim.id(),
                    claim.id(),
                    claim.paymentId(),
                    claim.eventType(),
                    claim.attempt(),
                    boundedError);
        }
    }
}
