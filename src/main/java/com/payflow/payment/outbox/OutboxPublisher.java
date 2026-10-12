package com.payflow.payment.outbox;

import com.payflow.payment.event.PaymentEventPublisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxStore store;
    private final PaymentEventPublisher kafka;
    private final int batchSize;
    private final long sendTimeoutMillis;

    public OutboxPublisher(
            OutboxStore store,
            PaymentEventPublisher kafka,
            @Value("${payflow.outbox.batch-size}") int batchSize,
            @Value("${payflow.outbox.send-timeout-ms}") long sendTimeoutMillis) {
        if (batchSize < 1 || sendTimeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "Outbox batch size and send timeout must be positive");
        }
        this.store = store;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int publishPending() {
        int attempted = 0;
        while (attempted < batchSize && !Thread.currentThread().isInterrupted()) {
            var next = store.claimNext();
            if (next.isEmpty()) {
                break;
            }
            OutboxClaim claim = next.get();
            attempted++;
            try {
                var sent =
                        claim.eventType().startsWith("REFUND_")
                                ? kafka.publishRefund(claim.paymentId(), claim.payload())
                                : kafka.publish(claim.paymentId(), claim.payload());
                sent.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                // Do not release a claim while an in-flight send might still complete; recover on
                // lease expiry.
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException exception) {
                // The send might still finish. Retain the lease rather than immediately competing
                // with it.
                store.recordUncertainSend(claim);
                log.warn(
                        "Outbox send deadline outboxId={} eventId={} paymentId={} eventType={}"
                                + " attempt={}",
                        claim.id(),
                        claim.id(),
                        claim.paymentId(),
                        claim.eventType(),
                        claim.attempt());
                continue;
            } catch (ExecutionException | RuntimeException exception) {
                Throwable cause =
                        exception instanceof ExecutionException ? exception.getCause() : exception;
                // Class name only: broker exception messages may contain credentials or addresses.
                store.recordFailure(
                        claim, "Kafka publish failed: " + cause.getClass().getSimpleName());
                continue;
            }
            try {
                if (store.markPublished(claim)) {
                    log.info(
                            "Outbox published outboxId={} eventId={} paymentId={} eventType={}"
                                    + " attempt={}",
                            claim.id(),
                            claim.id(),
                            claim.paymentId(),
                            claim.eventType(),
                            claim.attempt());
                } else {
                    log.warn(
                            "Outbox acknowledgment has stale claim outboxId={} eventId={}"
                                    + " paymentId={} eventType={} attempt={}",
                            claim.id(),
                            claim.id(),
                            claim.paymentId(),
                            claim.eventType(),
                            claim.attempt());
                }
            } catch (DataAccessException exception) {
                // Kafka acknowledged, but the database did not. Lease expiry replays the same
                // eventId.
                log.error(
                        "Outbox acknowledgment persistence failed outboxId={} eventId={}"
                                + " paymentId={} eventType={} attempt={}",
                        claim.id(),
                        claim.id(),
                        claim.paymentId(),
                        claim.eventType(),
                        claim.attempt());
            }
        }
        log.debug("Outbox poll completed attempted={} batchLimit={}", attempted, batchSize);
        return attempted;
    }
}
