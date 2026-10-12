package com.payflow.payment.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.dto.*;
import com.payflow.payment.entity.*;
import com.payflow.payment.event.RefundEventType;
import com.payflow.payment.exception.*;
import com.payflow.payment.repository.PaymentRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

@Service
public class RefundCreationStore {
    private final JdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final RefundStore refunds;
    private final ObjectMapper mapper;

    public RefundCreationStore(
            JdbcTemplate jdbc,
            PaymentRepository payments,
            RefundStore refunds,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.refunds = refunds;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundResponse create(UUID paymentId, CreateRefundRequest request, String key) {
        Payment payment =
                payments.findByIdForUpdate(paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return createLocked(payment, request, key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundResponse createOwned(UUID paymentId, String merchantId, CreateRefundRequest request, String key) {
        Payment payment = payments.findOwnedForUpdate(paymentId, merchantId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return createLocked(payment, request, key);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RefundResponse createLocked(Payment payment, CreateRefundRequest request, String key) {
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    mapper.writeValueAsBytes(
                                                            new CreateRefundRequest(
                                                                    request.amount()
                                                                            .stripTrailingZeros(),
                                                                    request
                                                                            .merchantRefundReference()))));
            var rows =
                    jdbc.query(
                            "SELECT request_hash,response::text FROM refund_idempotency WHERE"
                                    + " payment_id=? AND idempotency_key=?",
                            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
                            payment.getId(),
                            key);
            if (!rows.isEmpty()) {
                if (!hash.equals(rows.getFirst()[0]))
                    throw new RefundConflictException(
                            "Refund idempotency key conflicts with original request");
                return mapper.readValue(rows.getFirst()[1], RefundResponse.class);
            }
            if (!(payment.getStatus() == PaymentStatus.SUCCESS
                            || payment.getStatus() == PaymentStatus.PARTIALLY_REFUNDED)
                    || payment.getProvider() == null
                    || payment.getProviderTransactionId() == null
                    || payment.getProviderTransactionId().isBlank())
                throw new RefundConflictException("Payment is not refundable");
            if (request.amount().compareTo(remainingLocked(payment)) > 0)
                throw new RefundConflictException("Refund exceeds remaining refundable balance");
            UUID id = UUID.randomUUID();
            int inserted =
                    jdbc.update(
                            "INSERT INTO"
                                + " refunds(id,payment_id,amount,currency,status,merchant_refund_reference,provider)"
                                + " VALUES (?,?,?,?,'CREATED',?,?) ON"
                                + " CONFLICT(payment_id,merchant_refund_reference) DO NOTHING",
                            id,
                            payment.getId(),
                            request.amount(),
                            payment.getCurrency().getCurrencyCode(),
                            request.merchantRefundReference(),
                            payment.getProvider().name());
            if (inserted == 0)
                throw new RefundConflictException(
                        "Merchant refund reference already exists for payment");
            RefundResponse response = refunds.get(id);
            jdbc.update(
                    "INSERT INTO"
                        + " refund_idempotency(payment_id,idempotency_key,request_hash,refund_id,response)"
                        + " VALUES (?,?,?,?,?::jsonb)",
                    payment.getId(),
                    key,
                    hash,
                    id,
                    mapper.writeValueAsString(response));
            refunds.emit(id, RefundEventType.REFUND_CREATED);
            return response;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot persist refund intent", failure);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal remainingLocked(Payment payment) {
        BigDecimal used =
                jdbc.queryForObject(
                        "SELECT coalesce(sum(amount),0) FROM refunds WHERE payment_id=? AND status"
                                + " IN ('CREATED','PROCESSING','SUCCESS','PENDING_CONFIRMATION')",
                        BigDecimal.class,
                        payment.getId());
        return payment.getAmount().subtract(used);
    }
}
