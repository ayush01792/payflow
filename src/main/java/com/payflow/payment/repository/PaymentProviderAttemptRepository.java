package com.payflow.payment.repository;

import com.payflow.payment.entity.PaymentProviderAttempt;
import com.payflow.payment.entity.ProviderAttemptStatus;
import com.payflow.payment.provider.PaymentProviderType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentProviderAttemptRepository
        extends JpaRepository<PaymentProviderAttempt, UUID> {
    Optional<PaymentProviderAttempt>
            findFirstByPaymentIdAndProviderAndStatusInOrderByAttemptNumberDesc(
                    UUID paymentId,
                    PaymentProviderType provider,
                    List<ProviderAttemptStatus> statuses);

    @Query(
            "select coalesce(max(attempt.attemptNumber), 0) from PaymentProviderAttempt attempt"
                    + " where attempt.paymentId = :id")
    int lastAttemptNumber(@Param("id") UUID paymentId);

    boolean existsByPaymentIdAndProviderAndResponseAmbiguousTrue(
            UUID paymentId, PaymentProviderType provider);
}
