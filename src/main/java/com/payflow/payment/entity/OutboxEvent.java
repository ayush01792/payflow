package com.payflow.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    public OutboxEvent(
            UUID eventId,
            UUID paymentId,
            String eventType,
            int eventVersion,
            String payload,
            Instant createdAt) {
        this.id = eventId; // One durable event identity; retries never create a new UUID.
        this.aggregateType = "PAYMENT";
        this.aggregateId = paymentId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public static OutboxEvent refund(
            UUID eventId, UUID refundId, String type, int version, String payload, Instant now) {
        OutboxEvent row = new OutboxEvent(eventId, refundId, type, version, payload, now);
        row.aggregateType = "REFUND";
        return row;
    }

    public void claim(UUID token, Instant leaseUntil) {
        status = OutboxStatus.CLAIMED;
        claimToken = token;
        this.leaseUntil = leaseUntil;
        attemptCount++;
    }

    public void failExhaustedLease() {
        status = OutboxStatus.FAILED;
        lastError = "Publisher lease expired at maximum attempts";
        claimToken = null;
        leaseUntil = null;
    }
}
