package com.payflow.payment.service;

import com.payflow.payment.config.ProviderProperties;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.exception.PaymentNotFoundException;
import com.payflow.payment.observability.PayflowMetrics;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.*;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Every public operation is a short database transaction; this bean performs no HTTP calls. */
@Service
public class ProviderWorkflowStore {
    private final PaymentRepository payments;
    private final ProcessedKafkaEventRepository processed;
    private final PaymentProviderAttemptRepository attempts;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final PaymentProviderRouter router;
    private final ProviderProperties properties;
    private final JdbcTemplate jdbc;
    private final com.payflow.payment.reconciliation.ReconciliationStore reconciliation;

    private final PayflowMetrics metrics;

    public ProviderWorkflowStore(
            PaymentRepository payments,
            ProcessedKafkaEventRepository processed,
            PaymentProviderAttemptRepository attempts,
            OutboxService outbox,
            ApplicationEventPublisher events,
            PaymentProviderRouter router,
            ProviderProperties properties,
            JdbcTemplate jdbc,
            com.payflow.payment.reconciliation.ReconciliationStore reconciliation,
            PayflowMetrics metrics) {
        if (properties.getOrchestrationLease().isNegative()
                || properties.getOrchestrationLease().isZero()) {
            throw new IllegalArgumentException("Provider orchestration lease must be positive");
        }
        this.payments = payments;
        this.processed = processed;
        this.attempts = attempts;
        this.outbox = outbox;
        this.events = events;
        this.router = router;
        this.properties = properties;
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProviderClaim> claim(PaymentEvent event) {
        if (processed.existsById(event.eventId())) return Optional.empty();
        Payment payment = paymentForUpdate(event.paymentId());
        if (!payment.getMerchantId().equals(event.merchantId())
                || !payment.getUserId().equals(event.userId())
                || payment.getAmount().compareTo(event.amount()) != 0
                || !payment.getCurrency().getCurrencyCode().equals(event.currency())
                || payment.getPaymentMethod() != event.paymentMethod()) {
            throw new InvalidPaymentEventException("Event does not match persisted payment");
        }
        if (payment.getStatus() == PaymentStatus.SUCCESS
                || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                || payment.getStatus() == PaymentStatus.REFUNDED
                || payment.getStatus() == PaymentStatus.FAILED
                || payment.getStatus() == PaymentStatus.PENDING_CONFIRMATION) {
            processed.claim(event.eventId(), event.eventType().name());
            return Optional.empty(); // terminal/unknown states are never reversed by redelivery
        }
        if (payment.getStatus() != PaymentStatus.PROCESSING)
            throw new InvalidPaymentEventException("Provider requires PROCESSING payment");
        Instant now = now();
        if (payment.getOrchestrationToken() != null
                && payment.getOrchestrationLeaseUntil().isAfter(now)) {
            throw new ProviderWorkInProgressException();
        }
        PaymentProviderType provider =
                payment.getOrchestrationProvider() == null
                        ? router.primary(payment.getPaymentMethod()).type()
                        : payment.getOrchestrationProvider();
        UUID token = UUID.randomUUID();
        payment.setOrchestrationProvider(provider);
        payment.setOrchestrationToken(token);
        payment.setOrchestrationLeaseUntil(now.plus(properties.getOrchestrationLease()));
        return Optional.of(
                new ProviderClaim(
                        payment.getId(),
                        event.eventId(),
                        token,
                        provider,
                        new ProviderPaymentRequest(
                                payment.getId(),
                                payment.getMerchantId(),
                                payment.getUserId(),
                                payment.getAmount(),
                                payment.getCurrency().getCurrencyCode(),
                                payment.getPaymentMethod())));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentProviderAttempt startAttempt(ProviderClaim claim, PaymentProviderType provider) {
        Payment payment = ownedPayment(claim);
        if (!payment.getOrchestrationLeaseUntil().isAfter(now()))
            throw new ProviderWorkInProgressException();
        payment.setOrchestrationProvider(
                provider); // recovery stays with the last contacted provider/key
        int number = attempts.lastAttemptNumber(payment.getId()) + 1;
        return attempts.saveAndFlush(
                new PaymentProviderAttempt(
                        payment.getId(),
                        claim.eventId(),
                        provider,
                        claim.request().idempotencyKey(provider),
                        number,
                        now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAttempt(UUID id, ProviderPaymentResult result) {
        attempts.findById(id).orElseThrow().complete(result, now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failedAttempt(UUID id, ProviderTechnicalException failure) {
        attempts.findById(id).orElseThrow().technicalFailure(failure, now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasUncertainAttempts(UUID paymentId, PaymentProviderType provider) {
        return attempts.existsByPaymentIdAndProviderAndResponseAmbiguousTrue(paymentId, provider);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ProviderPaymentResult> knownBusinessResult(
            UUID paymentId, PaymentProviderType provider) {
        return attempts.findFirstByPaymentIdAndProviderAndStatusInOrderByAttemptNumberDesc(
                        paymentId,
                        provider,
                        java.util.List.of(
                                ProviderAttemptStatus.SUCCEEDED, ProviderAttemptStatus.DECLINED))
                .map(
                        attempt ->
                                new ProviderPaymentResult(
                                        provider,
                                        attempt.getProviderTransactionId(),
                                        attempt.getStatus() == ProviderAttemptStatus.SUCCEEDED
                                                ? ProviderPaymentOutcome.SUCCESS
                                                : ProviderPaymentOutcome.DECLINED,
                                        attempt.getFailureCode(),
                                        attempt.getFailureMessage()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishResult(ProviderClaim claim, ProviderPaymentResult result) {
        Payment payment = ownedPayment(claim);
        processed.claim(claim.eventId(), PaymentEventType.PAYMENT_PROCESSING.name());
        payment.setProvider(result.providerName());
        payment.setProviderTransactionId(result.providerTransactionId());
        payment.setFailureCode(result.failureCode());
        payment.setStatus(
                result.outcome() == ProviderPaymentOutcome.SUCCESS
                        ? PaymentStatus.SUCCESS
                        : PaymentStatus.FAILED);
        clearLease(payment);
        payments.flush();
        outbox.savePaymentEvent(
                PaymentEvent.forPayment(
                        payment.getStatus() == PaymentStatus.SUCCESS
                                ? PaymentEventType.PAYMENT_SUCCEEDED
                                : PaymentEventType.PAYMENT_FAILED,
                        PaymentMapper.toResponse(payment)));
        events.publishEvent(new PaymentStatusChanged(payment.getId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishTechnical(
            ProviderClaim claim, PaymentProviderType provider, ProviderTechnicalException failure) {
        Payment payment = ownedPayment(claim);
        processed.claim(claim.eventId(), PaymentEventType.PAYMENT_PROCESSING.name());
        payment.setProvider(provider);
        payment.setFailureCode(
                failure.ambiguous() ? "PROVIDER_RESULT_UNKNOWN" : failure.failureCode());
        payment.setStatus(
                failure.ambiguous() ? PaymentStatus.PENDING_CONFIRMATION : PaymentStatus.FAILED);
        clearLease(payment);
        payments.flush();
        if (payment.getStatus() == PaymentStatus.FAILED) {
            outbox.savePaymentEvent(
                    PaymentEvent.forPayment(
                            PaymentEventType.PAYMENT_FAILED, PaymentMapper.toResponse(payment)));
        }
        if (payment.getStatus() == PaymentStatus.PENDING_CONFIRMATION) metrics.paymentPending();
        if (payment.getStatus() == PaymentStatus.PENDING_CONFIRMATION)
            reconciliation.schedule(payment.getId());
        // An unknown provider result is not falsely announced as PAYMENT_FAILED/SUCCEEDED.
        events.publishEvent(new PaymentStatusChanged(payment.getId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAfterLocalFailure(ProviderClaim claim) {
        Payment payment = paymentForUpdate(claim.paymentId());
        if (claim.token().equals(payment.getOrchestrationToken())) clearLease(payment);
    }

    private Payment ownedPayment(ProviderClaim claim) {
        Payment payment = paymentForUpdate(claim.paymentId());
        if (payment.getStatus() != PaymentStatus.PROCESSING
                || !claim.token().equals(payment.getOrchestrationToken())) {
            throw new ProviderWorkInProgressException();
        }
        return payment;
    }

    private Payment paymentForUpdate(UUID id) {
        return payments.findByIdForUpdate(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    private void clearLease(Payment payment) {
        payment.setOrchestrationToken(null);
        payment.setOrchestrationLeaseUntil(null);
    }

    private Instant now() {
        Long micros =
                jdbc.queryForObject(
                        "SELECT CAST(FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000000) AS"
                                + " BIGINT)",
                        Long.class);
        return Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
    }
}
