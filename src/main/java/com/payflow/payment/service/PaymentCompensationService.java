package com.payflow.payment.service;

import com.payflow.payment.dto.*;
import com.payflow.payment.exception.*;
import com.payflow.payment.refund.RefundCreationStore;
import com.payflow.payment.repository.PaymentRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Internal compensation command. A refund is a new external business action, never XA rollback. */
@Service
public class PaymentCompensationService {
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final RefundCreationStore creation;

    public PaymentCompensationService(
            JdbcTemplate jdbc, PaymentRepository payments, RefundCreationStore creation) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.creation = creation;
    }

    @Transactional
    public CompensationResponse compensate(UUID compensationId, UUID paymentId, String reason) {
        if (compensationId == null
                || paymentId == null
                || reason == null
                || reason.isBlank()
                || reason.length() > 300)
            throw new InvalidRefundRequestException(
                    "Valid compensation ID, payment and reason required");
        var payment =
                payments.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        int inserted =
                jdbc.update(
                        "INSERT INTO payment_compensations(id,payment_id,reason,status) VALUES"
                                + " (?,?,?,'REQUESTED') ON CONFLICT(id) DO NOTHING",
                        compensationId,
                        paymentId,
                        reason);
        if (inserted == 0) {
            CompensationResponse original = get(compensationId);
            if (!original.paymentId().equals(paymentId) || !original.reason().equals(reason))
                throw new RefundConflictException(
                        "Compensation identifier conflicts with original command");
            return original;
        }
        var amount = creation.remainingLocked(payment);
        if (amount.signum() <= 0)
            throw new RefundConflictException("No available refundable amount for compensation");
        var refund =
                creation.createLocked(
                        payment,
                        new CreateRefundRequest(amount, "compensation:" + compensationId),
                        "compensation:" + compensationId);
        jdbc.update(
                "UPDATE payment_compensations SET"
                        + " refund_id=?,status='REFUND_CREATED',updated_at=clock_timestamp() WHERE"
                        + " id=?",
                refund.id(),
                compensationId);
        return get(compensationId);
    }

    @Transactional(readOnly = true)
    public CompensationResponse get(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM payment_compensations WHERE id=?",
                        (rs, n) ->
                                new CompensationResponse(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("payment_id", UUID.class),
                                        rs.getObject("refund_id", UUID.class),
                                        rs.getString("reason"),
                                        rs.getString("status")),
                        id);
        if (rows.isEmpty()) throw new RefundNotFoundException("Compensation not found");
        return rows.getFirst();
    }
}
