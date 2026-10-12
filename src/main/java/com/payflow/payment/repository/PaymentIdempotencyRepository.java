package com.payflow.payment.repository;

import java.util.Optional;
import java.util.UUID;

import com.payflow.payment.entity.PaymentIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency, UUID> {

    Optional<PaymentIdempotency> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);
}
