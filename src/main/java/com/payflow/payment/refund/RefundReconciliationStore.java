package com.payflow.payment.refund;

import com.payflow.payment.config.ReconciliationProperties;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.observability.PayflowMetrics;
import com.payflow.payment.provider.*;
import com.payflow.payment.reconciliation.ProviderWebhookInbox;
import com.payflow.payment.repository.PaymentRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

@Service
public class RefundReconciliationStore {
    private static final Logger log = LoggerFactory.getLogger(RefundReconciliationStore.class);
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final RefundStore refunds;
    private final ProviderWebhookInbox inbox;
    private final ReconciliationProperties properties;

    private final PayflowMetrics metrics;

    public RefundReconciliationStore(
            JdbcTemplate jdbc,
            PaymentRepository payments,
            RefundStore refunds,
            ProviderWebhookInbox inbox,
            ReconciliationProperties properties,
            PayflowMetrics metrics) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.refunds = refunds;
        this.inbox = inbox;
        this.metrics = metrics;
        this.properties = properties;
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
        var matches =
                jdbc.query(
                        "SELECT DISTINCT refund_id FROM refund_provider_attempts WHERE provider=?"
                                + " AND provider_idempotency_key=?",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        provider.name(),
                        request.providerReference());
        if (matches.size() != 1) {
            issue(
                    null,
                    provider,
                    null,
                    status.name(),
                    "WEBHOOK",
                    request.eventId(),
                    "UNKNOWN_REFUND_REFERENCE");
            inbox.complete(id, null, null, "UNMATCHED");
            return;
        }
        UUID refundId = matches.getFirst();
        var tasks =
                jdbc.queryForList(
                        "SELECT refund_id FROM refund_reconciliation_tasks WHERE refund_id=? FOR"
                                + " UPDATE",
                        refundId);
        var snapshot = refunds.get(refundId);
        var payment = payments.findByIdForUpdate(snapshot.paymentId()).orElseThrow();
        var refund = refunds.lock(refundId);
        if (refund.status() == RefundStatus.CREATED
                || refund.status() == RefundStatus.PROCESSING
                || (refund.status() == RefundStatus.PENDING_CONFIRMATION && tasks.isEmpty()))
            throw new WebhookRequestException(503, "Refund execution still in progress");
        String result =
                apply(
                        payment,
                        refund,
                        new ProviderRefundStatusResult(
                                provider,
                                request.providerReference(),
                                request.providerTransactionId(),
                                status,
                                request.amount(),
                                request.currency()),
                        "WEBHOOK",
                        request.eventId());
        inbox.complete(id, payment.getId(), refundId, result);
        log.info(
                "Refund webhook provider={} eventId={} refundId={} result={}",
                provider,
                request.eventId(),
                refundId,
                result);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RefundPollClaim> claim() {
        var exhausted =
                jdbc.query(
                        "UPDATE refund_reconciliation_tasks t SET"
                            + " escalated=true,owner_token=NULL,lease_until=NULL FROM refunds r"
                            + " WHERE r.id=t.refund_id AND r.status='PENDING_CONFIRMATION' AND NOT"
                            + " t.escalated AND t.attempt_count>=? AND (t.lease_until IS NULL OR"
                            + " t.lease_until<=clock_timestamp()) RETURNING t.refund_id",
                        (rs, n) -> rs.getObject(1, UUID.class),
                        properties.getMaxAttempts());
        for (UUID id : exhausted) {
            var refund = refunds.get(id);
            issue(
                    id,
                    refund.provider(),
                    refund.status().name(),
                    "UNKNOWN",
                    "STATUS_POLL",
                    id.toString(),
                    "MAX_ATTEMPTS_EXHAUSTED");
            jdbc.update(
                    "UPDATE refund_reconciliation_attempts SET"
                        + " result='ABANDONED',error_code='LEASE_EXPIRED',completed_at=clock_timestamp()"
                        + " WHERE refund_id=? AND result='STARTED'",
                    id);
        }
        var rows =
                jdbc.query(
                        "SELECT t.refund_id,t.attempt_count,r.provider FROM"
                            + " refund_reconciliation_tasks t JOIN refunds r ON r.id=t.refund_id"
                            + " WHERE r.status='PENDING_CONFIRMATION' AND NOT t.escalated AND"
                            + " t.attempt_count<? AND t.next_reconciliation_at<=clock_timestamp()"
                            + " AND (t.lease_until IS NULL OR t.lease_until<=clock_timestamp())"
                            + " ORDER BY t.next_reconciliation_at,t.refund_id LIMIT 1 FOR UPDATE OF"
                            + " t,r SKIP LOCKED",
                        (rs, n) -> {
                            UUID id = rs.getObject(1, UUID.class);
                            var provider = PaymentProviderType.valueOf(rs.getString(3));
                            return new RefundPollClaim(
                                    id,
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    rs.getInt(2) + 1,
                                    provider,
                                    RefundStore.key(id, provider));
                        },
                        properties.getMaxAttempts());
        if (rows.isEmpty()) return Optional.empty();
        var c = rows.getFirst();
        jdbc.update(
                "UPDATE refund_reconciliation_attempts SET"
                    + " result='ABANDONED',error_code='LEASE_EXPIRED',completed_at=clock_timestamp()"
                    + " WHERE refund_id=? AND result='STARTED'",
                c.refundId());
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET"
                        + " owner_token=?,lease_until=clock_timestamp()+(? * interval '1"
                        + " millisecond'),attempt_count=? WHERE refund_id=?",
                c.token(),
                properties.getLease().toMillis(),
                c.attempt(),
                c.refundId());
        jdbc.update(
                "INSERT INTO refund_reconciliation_attempts(id,refund_id,provider,attempt_number)"
                        + " VALUES (?,?,?,?)",
                c.attemptId(),
                c.refundId(),
                c.provider().name(),
                c.attempt());
        metrics.reconciliationAttempt(PayflowMetrics.Domain.REFUND);
        return Optional.of(c);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(RefundPollClaim claim, ProviderRefundStatusResult report, String error) {
        var owners =
                jdbc.queryForList(
                        "SELECT owner_token FROM refund_reconciliation_tasks WHERE refund_id=? AND"
                                + " owner_token=? AND lease_until>clock_timestamp() FOR UPDATE",
                        claim.refundId(),
                        claim.token());
        if (owners.isEmpty()) return false;
        var snapshot = refunds.get(claim.refundId());
        var payment = payments.findByIdForUpdate(snapshot.paymentId()).orElseThrow();
        var refund = refunds.lock(claim.refundId());
        String result =
                report == null
                        ? "UNAVAILABLE"
                        : apply(
                                payment,
                                refund,
                                report,
                                "STATUS_POLL",
                                claim.attemptId().toString());
        jdbc.update(
                "UPDATE refund_reconciliation_attempts SET"
                        + " result=?,error_code=?,completed_at=clock_timestamp() WHERE id=?",
                result,
                error,
                claim.attemptId());
        if (result.equals("UNAVAILABLE") || result.equals("CONFLICT"))
            metrics.reconciliationFailure(PayflowMetrics.Domain.REFUND);
        boolean pending = refunds.get(refund.id()).status() == RefundStatus.PENDING_CONFIRMATION;
        boolean exhausted = pending && claim.attempt() >= properties.getMaxAttempts();
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET"
                    + " owner_token=NULL,lease_until=NULL,escalated=?,next_reconciliation_at=clock_timestamp()+(?"
                    + " * interval '1 millisecond') WHERE refund_id=?",
                exhausted,
                properties.delayMillis(claim.attempt()),
                refund.id());
        if (exhausted)
            issue(
                    refund.id(),
                    refund.provider(),
                    refund.status().name(),
                    "UNKNOWN",
                    "STATUS_POLL",
                    refund.id().toString(),
                    "MAX_ATTEMPTS_EXHAUSTED");
        return true;
    }

    private String apply(
            Payment payment,
            RefundResponse refund,
            ProviderRefundStatusResult report,
            String source,
            String reference) {
        String reason = null;
        if (report.provider() != refund.provider()
                || !RefundStore.key(refund.id(), refund.provider())
                        .equals(report.providerReference())) reason = "PROVIDER_MISMATCH";
        else if (report.amount() != null && refund.amount().compareTo(report.amount()) != 0)
            reason = "AMOUNT_MISMATCH";
        else if (report.currency() != null && !refund.currency().equals(report.currency()))
            reason = "CURRENCY_MISMATCH";
        else if (refund.providerRefundId() != null
                && report.providerRefundId() != null
                && !refund.providerRefundId().equals(report.providerRefundId()))
            reason = "TRANSACTION_MISMATCH";
        else if (report.providerRefundId() != null
                && jdbc.queryForObject(
                                "SELECT count(*) FROM refund_provider_attempts WHERE refund_id=?"
                                        + " AND provider_refund_id IS NOT NULL AND"
                                        + " provider_refund_id<>?",
                                Integer.class,
                                refund.id(),
                                report.providerRefundId())
                        > 0) reason = "TRANSACTION_MISMATCH";
        boolean terminal =
                report.status() == ProviderStatusResult.Status.SUCCESS
                        || report.status() == ProviderStatusResult.Status.FAILED;
        var desired =
                report.status() == ProviderStatusResult.Status.SUCCESS
                        ? RefundStatus.SUCCESS
                        : RefundStatus.FAILED;
        if (reason == null
                && terminal
                && (refund.status() == RefundStatus.SUCCESS
                        || refund.status() == RefundStatus.FAILED)
                && refund.status() != desired) reason = "TERMINAL_STATUS_CONFLICT";
        if (reason != null) {
            issue(
                    refund.id(),
                    report.provider(),
                    refund.status().name(),
                    report.status().name(),
                    source,
                    reference,
                    reason);
            return "CONFLICT";
        }
        if (!terminal) return "PENDING";
        if (refund.status() == desired) return "CONFIRMED";
        if (refund.status() != RefundStatus.PENDING_CONFIRMATION) {
            issue(
                    refund.id(),
                    report.provider(),
                    refund.status().name(),
                    report.status().name(),
                    source,
                    reference,
                    "INELIGIBLE_REFUND_STATE");
            return "CONFLICT";
        }
        refunds.applyLocked(
                payment,
                refund,
                desired,
                report.providerRefundId(),
                desired == RefundStatus.FAILED ? "PROVIDER_CONFIRMED_REFUND_FAILURE" : null,
                null);
        jdbc.update(
                "UPDATE refund_reconciliation_tasks SET escalated=false WHERE refund_id=?",
                refund.id());
        jdbc.update(
                "UPDATE payment_reconciliation_issues SET resolution_status='RESOLVED' WHERE"
                        + " refund_id=? AND reason='MAX_ATTEMPTS_EXHAUSTED'",
                refund.id());
        metrics.reconciliationResolution(
                PayflowMetrics.Domain.REFUND, desired == RefundStatus.SUCCESS);
        return "APPLIED";
    }

    private void issue(
            UUID refund,
            PaymentProviderType provider,
            String existing,
            String reported,
            String source,
            String reference,
            String reason) {
        jdbc.update(
                "INSERT INTO"
                    + " payment_reconciliation_issues(id,refund_id,provider,existing_status,reported_status,source,source_reference,reason)"
                    + " VALUES (?,?,?,?,?,?,?,?) ON"
                    + " CONFLICT(source,provider,source_reference,reason) DO NOTHING",
                UUID.randomUUID(),
                refund,
                provider.name(),
                existing,
                reported,
                source,
                reference,
                reason);
        log.warn(
                "Refund reconciliation conflict refundId={} provider={} source={} reason={}",
                refund,
                provider,
                source,
                reason);
    }
}
