package com.payflow.payment.outbox;

import java.util.UUID;

/** Snapshot detached from the short claim transaction, safe to use during network I/O. */
public record OutboxClaim(UUID id, UUID paymentId, String eventType, String payload, UUID token, int attempt) {
}
