package com.payflow.payment.reconciliation;

import com.payflow.payment.config.ReconciliationProperties;
import com.payflow.payment.dto.ProviderWebhookRequest;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.observability.PayflowMetrics;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.service.OutboxService;
import com.payflow.payment.service.PaymentMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** SQL audit/task persistence and JPA payment changes share the same PostgreSQL transaction. */
@Service
public class ReconciliationStore {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationStore.class);
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final ReconciliationProperties properties;
    private final ProviderWebhookInbox inbox;

    private final PayflowMetrics metrics;

    public ReconciliationStore(
            JdbcTemplate jdbc,
            PaymentRepository payments,
            OutboxService outbox,
            ApplicationEventPublisher events,
            ReconciliationProperties properties,
            ProviderWebhookInbox inbox,
            PayflowMetrics metrics) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.outbox = outbox;
        this.events = events;
        this.properties = properties;
        this.inbox = inbox;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(UUID paymentId) {
        jdbc.update(
                "INSERT INTO payment_reconciliation_tasks(payment_id,next_reconciliation_at) VALUES"
                        + " (?,clock_timestamp()+(? * interval '1 millisecond')) ON"
                        + " CONFLICT(payment_id) DO NOTHING",
                paymentId,
                properties.getInitialDelay().toMillis());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void webhook(
            PaymentProviderType provider,
            ProviderWebhookRequest request,
            String hash,
            ProviderStatusResult.Status status) {
        var accepted = inbox.accept(provider, request, hash);
        if (accepted.isEmpty()) return;
        UUID id = accepted.get();
        List<UUID> matches =
                jdbc.query(
                        "SELECT DISTINCT payment_id FROM payment_provider_attempts WHERE provider=?"
                                + " AND provider_idempotency_key=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        provider.name(),
                        request.providerReference());
        String result;
        UUID paymentId = matches.size() == 1 ? matches.getFirst() : null;
        if (paymentId == null) {
            issue(
                    null,
                    provider,
                    null,
                    status.name(),
                    "WEBHOOK",
                    request.eventId(),
                    "UNKNOWN_PROVIDER_REFERENCE");
            result = "UNMATCHED";
        } else {
            // All paths that touch both rows lock task before payment, including FK checks.
            var lockedTasks =
                    jdbc.query(
                            "SELECT payment_id FROM payment_reconciliation_tasks WHERE payment_id=?"
                                    + " FOR UPDATE",
                            (rs, n) -> rs.getObject(1, UUID.class),
                            paymentId);
            Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
            if (payment.getStatus() == PaymentStatus.PENDING_CONFIRMATION && lockedTasks.isEmpty())
                throw new WebhookRequestException(
                        503, "Payment reconciliation task is being initialized");
            // An early terminal webhook is retried by the mock with the SAME event ID after
            // orchestration settles.
            if (payment.getStatus() == PaymentStatus.PROCESSING
                    && (status == ProviderStatusResult.Status.SUCCESS
                            || status == ProviderStatusResult.Status.FAILED))
                throw new WebhookRequestException(503, "Payment execution still in progress");
            result =
                    apply(
                            payment,
                            new ProviderStatusResult(
                                    provider,
                                    request.providerReference(),
                                    request.providerTransactionId(),
                                    status,
                                    request.amount(),
                                    request.currency()),
                            "WEBHOOK",
                            request.eventId());
        }
        inbox.complete(id, paymentId, null, result);
        log.info(
                "Webhook processed provider={} providerEventId={} paymentId={}"
                        + " providerTransactionId={} result={}",
                provider,
                request.eventId(),
                paymentId,
                request.providerTransactionId(),
                result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ReconciliationClaim> claim() {
        // Expired final attempts are escalated, never translated to payment failure.
        List<UUID> exhausted =
                jdbc.query(
                        "UPDATE payment_reconciliation_tasks t SET"
                            + " escalated=true,owner_token=NULL,lease_until=NULL FROM payments p"
                            + " WHERE p.id=t.payment_id AND p.status='PENDING_CONFIRMATION' AND NOT"
                            + " t.escalated AND t.attempt_count>=? AND (t.lease_until IS NULL OR"
                            + " t.lease_until<=clock_timestamp()) RETURNING t.payment_id",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        properties.getMaxAttempts());
        for (UUID id : exhausted) {
            Payment p = payments.findById(id).orElseThrow();
            issue(
                    id,
                    p.getProvider(),
                    p.getStatus().name(),
                    "UNKNOWN",
                    "STATUS_POLL",
                    id.toString(),
                    "MAX_ATTEMPTS_EXHAUSTED");
            jdbc.update(
                    "UPDATE payment_reconciliation_attempts SET"
                        + " result='ABANDONED',completed_at=clock_timestamp(),error_code='LEASE_EXPIRED'"
                        + " WHERE payment_id=? AND result='STARTED'",
                    id);
        }
        List<ReconciliationClaim> rows =
                jdbc.query(
                        """
SELECT t.payment_id,t.attempt_count,p.provider FROM payment_reconciliation_tasks t
JOIN payments p ON p.id=t.payment_id
WHERE p.status='PENDING_CONFIRMATION' AND NOT t.escalated AND t.attempt_count<?
  AND t.next_reconciliation_at<=clock_timestamp()
  AND (t.lease_until IS NULL OR t.lease_until<=clock_timestamp())
ORDER BY t.next_reconciliation_at,t.payment_id LIMIT 1 FOR UPDATE OF t,p SKIP LOCKED
""",
                        (rs, n) -> {
                            UUID id = rs.getObject(1, UUID.class);
                            PaymentProviderType provider =
                                    PaymentProviderType.valueOf(rs.getString(3));
                            return new ReconciliationClaim(
                                    id,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    rs.getInt(2) + 1,
                                    provider,
                                    "payflow:" + id + ":" + provider);
                        },
                        properties.getMaxAttempts());
        if (rows.isEmpty()) return Optional.empty();
        ReconciliationClaim claim = rows.getFirst();
        jdbc.update(
                "UPDATE payment_reconciliation_attempts SET"
                    + " result='ABANDONED',completed_at=clock_timestamp(),error_code='LEASE_EXPIRED'"
                    + " WHERE payment_id=? AND result='STARTED'",
                claim.paymentId());
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET"
                        + " owner_token=?,lease_until=clock_timestamp()+(? * interval '1"
                        + " millisecond'),attempt_count=? WHERE payment_id=?",
                claim.token(),
                properties.getLease().toMillis(),
                claim.attemptNumber(),
                claim.paymentId());
        jdbc.update(
                "INSERT INTO payment_reconciliation_attempts(id,payment_id,provider,attempt_number)"
                        + " VALUES (?,?,?,?)",
                claim.attemptId(),
                claim.paymentId(),
                claim.provider().name(),
                claim.attemptNumber());
        metrics.reconciliationAttempt(PayflowMetrics.Domain.PAYMENT);
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(
            ReconciliationClaim claim, ProviderStatusResult status, String errorCode) {
        List<UUID> owners =
                jdbc.query(
                        "SELECT owner_token FROM payment_reconciliation_tasks WHERE payment_id=?"
                                + " AND owner_token=? AND lease_until>clock_timestamp() FOR UPDATE",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        claim.paymentId(),
                        claim.token());
        if (owners.isEmpty())
            return false; // stale worker is fenced from state, task and audit updates
        Payment payment = payments.findByIdForUpdate(claim.paymentId()).orElseThrow();
        String result =
                status == null
                        ? "UNAVAILABLE"
                        : apply(payment, status, "STATUS_POLL", claim.attemptId().toString());
        jdbc.update(
                "UPDATE payment_reconciliation_attempts SET"
                        + " result=?,error_code=?,completed_at=clock_timestamp() WHERE id=?",
                result,
                errorCode,
                claim.attemptId());
        if (result.equals("UNAVAILABLE") || result.equals("CONFLICT"))
            metrics.reconciliationFailure(PayflowMetrics.Domain.PAYMENT);
        boolean pending = payment.getStatus() == PaymentStatus.PENDING_CONFIRMATION;
        boolean exhausted = pending && claim.attemptNumber() >= properties.getMaxAttempts();
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET"
                    + " owner_token=NULL,lease_until=NULL,escalated=?,next_reconciliation_at=clock_timestamp()+(?"
                    + " * interval '1 millisecond') WHERE payment_id=?",
                exhausted,
                properties.delayMillis(claim.attemptNumber()),
                claim.paymentId());
        if (exhausted)
            issue(
                    payment.getId(),
                    claim.provider(),
                    payment.getStatus().name(),
                    "UNKNOWN",
                    "STATUS_POLL",
                    payment.getId().toString(),
                    "MAX_ATTEMPTS_EXHAUSTED");
        log.info(
                "Reconciliation paymentId={} provider={} source=STATUS_POLL attempt={} result={}",
                claim.paymentId(),
                claim.provider(),
                claim.attemptNumber(),
                result);
        return true;
    }

    private String apply(
            Payment payment, ProviderStatusResult report, String source, String reference) {
        String reason = null;
        String expectedKey = "payflow:" + payment.getId() + ":" + report.provider();
        Integer contacted =
                jdbc.queryForObject(
                        "SELECT count(*) FROM payment_provider_attempts WHERE payment_id=? AND"
                                + " provider=? AND provider_idempotency_key=?",
                        Integer.class,
                        payment.getId(),
                        report.provider().name(),
                        expectedKey);
        if (report.provider() != payment.getProvider()
                || contacted == null
                || contacted == 0
                || !expectedKey.equals(report.providerReference())) reason = "PROVIDER_MISMATCH";
        else if (report.amount() != null && payment.getAmount().compareTo(report.amount()) != 0)
            reason = "AMOUNT_MISMATCH";
        else if (report.currency() != null
                && !payment.getCurrency().getCurrencyCode().equals(report.currency()))
            reason = "CURRENCY_MISMATCH";
        else if (payment.getProviderTransactionId() != null
                && report.providerTransactionId() != null
                && !payment.getProviderTransactionId().equals(report.providerTransactionId()))
            reason = "TRANSACTION_MISMATCH";
        else if (report.providerTransactionId() != null
                && jdbc.queryForObject(
                                "SELECT count(*) FROM payment_provider_attempts WHERE payment_id=?"
                                    + " AND provider=? AND provider_transaction_id IS NOT NULL AND"
                                    + " provider_transaction_id<>?",
                                Integer.class,
                                payment.getId(),
                                report.provider().name(),
                                report.providerTransactionId())
                        > 0) reason = "TRANSACTION_MISMATCH";
        boolean captured =
                payment.getStatus() == PaymentStatus.SUCCESS
                        || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED
                        || payment.getStatus() == PaymentStatus.REFUNDED;
        boolean terminal =
                report.status() == ProviderStatusResult.Status.SUCCESS
                        || report.status() == ProviderStatusResult.Status.FAILED;
        PaymentStatus desired =
                report.status() == ProviderStatusResult.Status.SUCCESS
                        ? PaymentStatus.SUCCESS
                        : PaymentStatus.FAILED;
        if (reason == null
                && terminal
                && (captured || payment.getStatus() == PaymentStatus.FAILED)
                && !(captured && desired == PaymentStatus.SUCCESS)
                && payment.getStatus() != desired) reason = "TERMINAL_STATUS_CONFLICT";
        if (reason != null) {
            issue(
                    payment.getId(),
                    report.provider(),
                    payment.getStatus().name(),
                    report.status().name(),
                    source,
                    reference,
                    reason);
            return "CONFLICT";
        }
        if (!terminal) return "PENDING";
        if (payment.getStatus() == desired || (captured && desired == PaymentStatus.SUCCESS))
            return "CONFIRMED";
        if (payment.getStatus() != PaymentStatus.PENDING_CONFIRMATION) {
            issue(
                    payment.getId(),
                    report.provider(),
                    payment.getStatus().name(),
                    report.status().name(),
                    source,
                    reference,
                    "INELIGIBLE_PAYMENT_STATE");
            return "CONFLICT";
        }
        payment.setStatus(desired);
        payment.setProviderTransactionId(report.providerTransactionId());
        payment.setFailureCode(
                desired == PaymentStatus.FAILED ? "PROVIDER_CONFIRMED_FAILURE" : null);
        payments.flush();
        jdbc.update(
                "UPDATE payment_reconciliation_tasks SET escalated=false WHERE payment_id=?",
                payment.getId());
        jdbc.update(
                "UPDATE payment_reconciliation_issues SET resolution_status='RESOLVED' WHERE"
                        + " payment_id=? AND reason='MAX_ATTEMPTS_EXHAUSTED' AND"
                        + " resolution_status='OPEN'",
                payment.getId());
        outbox.savePaymentEvent(
                PaymentEvent.forPayment(
                        desired == PaymentStatus.SUCCESS
                                ? PaymentEventType.PAYMENT_SUCCEEDED
                                : PaymentEventType.PAYMENT_FAILED,
                        PaymentMapper.toResponse(payment)));
        events.publishEvent(new PaymentStatusChanged(payment.getId()));
        metrics.reconciliationResolution(
                PayflowMetrics.Domain.PAYMENT, desired == PaymentStatus.SUCCESS);
        return "APPLIED";
    }

    private void issue(
            UUID paymentId,
            PaymentProviderType provider,
            String existing,
            String reported,
            String source,
            String reference,
            String reason) {
        jdbc.update(
                "INSERT INTO"
                    + " payment_reconciliation_issues(id,payment_id,provider,existing_status,reported_status,source,source_reference,reason)"
                    + " VALUES (?,?,?,?,?,?,?,?) ON"
                    + " CONFLICT(source,provider,source_reference,reason) DO NOTHING",
                UUID.randomUUID(),
                paymentId,
                provider == null ? "UNKNOWN" : provider.name(),
                existing,
                reported,
                source,
                reference,
                reason);
        log.warn(
                "Reconciliation conflict paymentId={} provider={} source={} reference={} reason={}",
                paymentId,
                provider,
                source,
                reference,
                reason);
    }
}
