package com.payflow.payment.entity;

import com.payflow.payment.provider.*;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_provider_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentProviderAttempt {
    @Id private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProviderType provider;

    @Column(name = "provider_idempotency_key", nullable = false, length = 100)
    private String providerIdempotencyKey;

    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderAttemptStatus status;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "failure_message", length = 300)
    private String failureMessage;

    @Column(name = "response_ambiguous", nullable = false)
    private boolean responseAmbiguous;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public PaymentProviderAttempt(
            UUID paymentId,
            UUID eventId,
            PaymentProviderType provider,
            String key,
            int number,
            Instant now) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.sourceEventId = eventId;
        this.provider = provider;
        this.providerIdempotencyKey = key;
        this.attemptNumber = number;
        this.status = ProviderAttemptStatus.STARTED;
        this.startedAt = now;
        this.responseAmbiguous = true;
    }

    public void complete(ProviderPaymentResult result, Instant now) {
        this.status =
                result.outcome() == ProviderPaymentOutcome.SUCCESS
                        ? ProviderAttemptStatus.SUCCEEDED
                        : ProviderAttemptStatus.DECLINED;
        this.providerTransactionId = result.providerTransactionId();
        this.failureCode = result.failureCode();
        this.failureMessage =
                result.failureMessage() == null
                        ? null
                        : result.failureMessage()
                                .substring(0, Math.min(300, result.failureMessage().length()));
        this.responseAmbiguous = false;
        this.completedAt = now;
    }

    public void technicalFailure(ProviderTechnicalException failure, Instant now) {
        this.status = ProviderAttemptStatus.TECHNICAL_FAILURE;
        this.failureCode = failure.failureCode();
        this.failureMessage = "Provider call did not return a usable business result";
        this.responseAmbiguous = failure.ambiguous();
        this.completedAt = now;
    }
}
