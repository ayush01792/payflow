package com.payflow.payment.service;

import java.util.Currency;
import java.util.Optional;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentIdempotency;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.exception.IdempotencyConflictException;
import com.payflow.payment.repository.PaymentIdempotencyRepository;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.event.PaymentEvent;
import com.payflow.payment.event.PaymentEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Separate Spring bean so collision recovery happens after the failed transaction rolls back. */
@Service
public class IdempotencyService {

    static final String KEY_CONSTRAINT = "uk_payment_idempotency_merchant_key";
    private final PaymentRepository payments;
    private final PaymentIdempotencyRepository idempotency;

    private final OutboxService outbox;

    public IdempotencyService(PaymentRepository payments, PaymentIdempotencyRepository idempotency,
                              OutboxService outbox) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public Optional<PaymentCreationResult> findReplay(String merchantId, String key, String hash) {
        return idempotency.findByMerchantIdAndIdempotencyKey(merchantId, key)
                .map(record -> replay(record, hash));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public PaymentCreationResult create(String merchantId, CreatePaymentRequest request, String key, String hash, Currency currency) {
        var existing = idempotency.findByMerchantIdAndIdempotencyKey(merchantId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        Payment payment = payments.saveAndFlush(new Payment(merchantId, request.userId(),
                request.amount(), currency, request.paymentMethod(), PaymentStatus.CREATED));
        // A collision rolls back BOTH inserts. Flush here to surface it before returning.
        idempotency.saveAndFlush(new PaymentIdempotency(merchantId, key, hash, payment.getId()));
        var response = PaymentMapper.toResponse(payment);
        outbox.savePaymentEvent(PaymentEvent.forPayment(PaymentEventType.PAYMENT_CREATED, response));
        return new PaymentCreationResult(response, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = true)
    public PaymentCreationResult replay(String merchantId, String key, String hash) {
        PaymentIdempotency record = idempotency.findByMerchantIdAndIdempotencyKey(merchantId, key)
                .orElseThrow(() -> new IllegalStateException("Committed idempotency record is missing"));
        return replay(record, hash);
    }

    private PaymentCreationResult replay(PaymentIdempotency record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException();
        }
        Payment payment = payments.findById(record.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("Referenced payment is missing"));
        return new PaymentCreationResult(PaymentMapper.toResponse(payment), false);
    }
}
