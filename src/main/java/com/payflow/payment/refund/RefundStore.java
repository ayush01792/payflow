package com.payflow.payment.refund;

import com.payflow.payment.config.*;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.*;
import com.payflow.payment.exception.*;
import com.payflow.payment.provider.*;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.service.OutboxService;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@Service
public class RefundStore {
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final ApplicationEventPublisher events;
    private final ProviderProperties properties;
    private final ReconciliationProperties reconciliation;

    public RefundStore(
            JdbcTemplate jdbc,
            PaymentRepository payments,
            OutboxService outbox,
            ApplicationEventPublisher events,
            ProviderProperties properties,
            ReconciliationProperties reconciliation) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.outbox = outbox;
        this.events = events;
        this.properties = properties;
        this.reconciliation = reconciliation;
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID id) {
        return row(id, false);
    }

    private RefundResponse row(UUID id, boolean lock) {
        var rows =
                jdbc.query(
                        "SELECT * FROM refunds WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                        (rs, n) ->
                                new RefundResponse(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("currency"),
                                        RefundStatus.valueOf(rs.getString("status")),
                                        rs.getString("merchant_refund_reference"),
                                        PaymentProviderType.valueOf(rs.getString("provider")),
                                        rs.getString("provider_refund_id"),
                                        rs.getString("failure_code"),
                                        rs.getString("failure_message"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant()),
                        id);
        if (rows.isEmpty()) throw new RefundNotFoundException("Refund not found");
        return rows.getFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void emit(UUID id, RefundEventType type) {
        RefundResponse refund = row(id, false);
        String merchant =
                jdbc.queryForObject(
                        "SELECT merchant_id FROM payments WHERE id=?",
                        String.class,
                        refund.paymentId());
        outbox.saveRefundEvent(RefundEvent.forRefund(type, refund, merchant));
    }

    private void validateSnapshot(RefundEvent event, RefundResponse refund) {
        String merchant =
                jdbc.queryForObject(
                        "SELECT merchant_id FROM payments WHERE id=?",
                        String.class,
                        refund.paymentId());
        if (!event.paymentId().equals(refund.paymentId())
                || !event.merchantId().equals(merchant)
                || event.amount().compareTo(refund.amount()) != 0
                || !event.currency().equals(refund.currency()))
            throw new InvalidPaymentEventException("Refund event does not match persisted refund");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void lifecycle(RefundEvent event) {
        if (jdbc.update(
                        "INSERT INTO processed_refund_events(event_id,event_type) VALUES (?,?) ON"
                                + " CONFLICT DO NOTHING",
                        event.eventId(),
                        event.eventType().name())
                == 0) return;
        RefundResponse refund = row(event.refundId(), true);
        validateSnapshot(event, refund);
        if (event.eventType() == RefundEventType.REFUND_CREATED
                && refund.status() == RefundStatus.CREATED) {
            jdbc.update(
                    "UPDATE refunds SET status='PROCESSING',updated_at=clock_timestamp() WHERE"
                            + " id=?",
                    refund.id());
            emit(refund.id(), RefundEventType.REFUND_PROCESSING);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<RefundClaim> claim(RefundEvent event) {
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM processed_refund_events WHERE event_id=?)",
                        Boolean.class,
                        event.eventId()))) return Optional.empty();
        RefundResponse refund = row(event.refundId(), true);
        validateSnapshot(event, refund);
        if (refund.status() == RefundStatus.SUCCESS
                || refund.status() == RefundStatus.FAILED
                || refund.status() == RefundStatus.PENDING_CONFIRMATION) {
            mark(event);
            return Optional.empty();
        }
        if (refund.status() != RefundStatus.PROCESSING)
            throw new InvalidPaymentEventException("Refund must be PROCESSING");
        if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT owner_token IS NOT NULL AND lease_until>clock_timestamp() FROM"
                                + " refunds WHERE id=?",
                        Boolean.class,
                        refund.id()))) throw new ProviderWorkInProgressException();
        UUID token = UUID.randomUUID();
        jdbc.update(
                "UPDATE refunds SET owner_token=?,lease_until=clock_timestamp()+(? * interval '1"
                        + " millisecond') WHERE id=?",
                token,
                properties.getOrchestrationLease().toMillis(),
                refund.id());
        String transaction =
                jdbc.queryForObject(
                        "SELECT provider_transaction_id FROM payments WHERE id=?",
                        String.class,
                        refund.paymentId());
        return Optional.of(
                new RefundClaim(
                        refund.id(),
                        event.eventId(),
                        token,
                        refund.provider(),
                        new ProviderRefundRequest(
                                refund.id(),
                                refund.paymentId(),
                                transaction,
                                refund.amount(),
                                refund.currency(),
                                key(refund.id(), refund.provider()))));
    }

    public static String key(UUID id, PaymentProviderType provider) {
        return "payflow-refund:" + id + ":" + provider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ProviderRefundResult> knownResult(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM refund_provider_attempts WHERE refund_id=? AND status IN"
                                + " ('SUCCEEDED','DECLINED') ORDER BY attempt_number DESC LIMIT 1",
                        (rs, n) ->
                                new ProviderRefundResult(
                                        PaymentProviderType.valueOf(rs.getString("provider")),
                                        rs.getString("provider_refund_id"),
                                        rs.getString("status").equals("SUCCEEDED")
                                                ? ProviderRefundResult.Outcome.SUCCESS
                                                : ProviderRefundResult.Outcome.DECLINED,
                                        rs.getString("failure_code"),
                                        rs.getString("failure_message")),
                        id);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean uncertain(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM refund_provider_attempts WHERE refund_id=? AND"
                                + " ambiguous)",
                        Boolean.class,
                        id));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID startAttempt(RefundClaim claim) {
        row(claim.refundId(), true);
        if (!owned(claim)) throw new ProviderWorkInProgressException();
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " refund_provider_attempts(id,refund_id,provider,provider_idempotency_key,attempt_number,status)"
                    + " SELECT ?,?,?,?,coalesce(max(attempt_number),0)+1,'STARTED' FROM"
                    + " refund_provider_attempts WHERE refund_id=?",
                id,
                claim.refundId(),
                claim.provider().name(),
                claim.request().providerRefundIdempotencyKey(),
                claim.refundId());
        return id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAttempt(UUID id, ProviderRefundResult result) {
        jdbc.update(
                "UPDATE refund_provider_attempts SET"
                    + " provider_refund_id=?,status=?,failure_code=?,failure_message=?,ambiguous=false,completed_at=clock_timestamp()"
                    + " WHERE id=?",
                result.providerRefundId(),
                result.outcome() == ProviderRefundResult.Outcome.SUCCESS ? "SUCCEEDED" : "DECLINED",
                result.failureCode(),
                bounded(result.failureMessage()),
                id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAttempt(UUID id, ProviderTechnicalException error) {
        jdbc.update(
                "UPDATE refund_provider_attempts SET"
                    + " status='TECHNICAL_FAILURE',failure_code=?,failure_message='Provider refund"
                    + " response unavailable',ambiguous=?,completed_at=clock_timestamp() WHERE"
                    + " id=?",
                error.failureCode(),
                error.ambiguous(),
                id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(
            RefundClaim claim, ProviderRefundResult result, ProviderTechnicalException failure) {
        UUID paymentId =
                jdbc.queryForObject(
                        "SELECT payment_id FROM refunds WHERE id=?", UUID.class, claim.refundId());
        Payment payment = payments.findByIdForUpdate(paymentId).orElseThrow();
        RefundResponse refund = row(claim.refundId(), true);
        if (!owned(claim)) throw new ProviderWorkInProgressException();
        RefundStatus status =
                failure == null
                        ? (result.outcome() == ProviderRefundResult.Outcome.SUCCESS
                                ? RefundStatus.SUCCESS
                                : RefundStatus.FAILED)
                        : (failure.ambiguous()
                                ? RefundStatus.PENDING_CONFIRMATION
                                : RefundStatus.FAILED);
        applyLocked(
                payment,
                refund,
                status,
                result == null ? null : result.providerRefundId(),
                failure == null
                        ? result.failureCode()
                        : failure.ambiguous()
                                ? "PROVIDER_REFUND_RESULT_UNKNOWN"
                                : failure.failureCode(),
                result == null ? "Provider refund result unavailable" : result.failureMessage());
        jdbc.update(
                "INSERT INTO processed_refund_events(event_id,event_type) VALUES"
                        + " (?,'REFUND_PROCESSING') ON CONFLICT DO NOTHING",
                claim.eventId());
    }

    private boolean owned(RefundClaim c) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT owner_token=? AND lease_until>clock_timestamp() AND"
                                + " status='PROCESSING' FROM refunds WHERE id=?",
                        Boolean.class,
                        c.token(),
                        c.refundId()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(RefundClaim c) {
        jdbc.update(
                "UPDATE refunds SET owner_token=NULL,lease_until=NULL WHERE id=? AND owner_token=?",
                c.refundId(),
                c.token());
    }

    private void mark(RefundEvent event) {
        jdbc.update(
                "INSERT INTO processed_refund_events(event_id,event_type) VALUES (?,?) ON CONFLICT"
                        + " DO NOTHING",
                event.eventId(),
                event.eventType().name());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundResponse lock(UUID id) {
        return row(id, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLocked(
            Payment payment,
            RefundResponse refund,
            RefundStatus status,
            String providerRefundId,
            String code,
            String message) {
        jdbc.update(
                "UPDATE refunds SET"
                    + " status=?,provider_refund_id=?,failure_code=?,failure_message=?,owner_token=NULL,lease_until=NULL,updated_at=clock_timestamp()"
                    + " WHERE id=?",
                status.name(),
                providerRefundId,
                code,
                bounded(message),
                refund.id());
        if (status == RefundStatus.SUCCESS) {
            BigDecimal total =
                    jdbc.queryForObject(
                            "SELECT coalesce(sum(amount),0) FROM refunds WHERE payment_id=? AND"
                                    + " status='SUCCESS'",
                            BigDecimal.class,
                            payment.getId());
            if (total.compareTo(payment.getAmount()) > 0)
                throw new IllegalStateException("Refund reservation invariant violated");
            payment.setStatus(
                    total.compareTo(payment.getAmount()) == 0
                            ? PaymentStatus.REFUNDED
                            : PaymentStatus.PARTIALLY_REFUNDED);
            payments.flush();
            events.publishEvent(new PaymentStatusChanged(payment.getId()));
        }
        if (status == RefundStatus.PENDING_CONFIRMATION)
            jdbc.update(
                    "INSERT INTO refund_reconciliation_tasks(refund_id,next_reconciliation_at)"
                        + " VALUES (?,clock_timestamp()+(? * interval '1 millisecond')) ON CONFLICT"
                        + " DO NOTHING",
                    refund.id(),
                    reconciliation.getInitialDelay().toMillis());
        jdbc.update(
                "UPDATE payment_compensations SET status=?,updated_at=clock_timestamp() WHERE"
                        + " refund_id=?",
                status == RefundStatus.SUCCESS
                        ? "COMPLETED"
                        : status == RefundStatus.FAILED ? "FAILED" : "PENDING_CONFIRMATION",
                refund.id());
        emit(
                refund.id(),
                status == RefundStatus.SUCCESS
                        ? RefundEventType.REFUND_SUCCEEDED
                        : status == RefundStatus.FAILED
                                ? RefundEventType.REFUND_FAILED
                                : RefundEventType.REFUND_PENDING_CONFIRMATION);
    }

    private String bounded(String value) {
        return value == null ? null : value.substring(0, Math.min(300, value.length()));
    }
}
