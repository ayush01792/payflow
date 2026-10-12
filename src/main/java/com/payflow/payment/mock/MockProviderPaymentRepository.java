package com.payflow.payment.mock;

import com.payflow.payment.provider.PaymentProviderType;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MockProviderPaymentRepository extends JpaRepository<MockProviderPayment, UUID> {
    @Modifying
    @Query(
            value =
                    """
INSERT INTO mock_provider_payments (id, provider, provider_idempotency_key, payment_id, request_hash)
VALUES (:id, :provider, :key, :paymentId, :hash)
ON CONFLICT (provider, provider_idempotency_key) DO NOTHING
""",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("key") String key,
            @Param("paymentId") UUID paymentId,
            @Param("hash") String hash);

    Optional<MockProviderPayment> findByProviderAndProviderIdempotencyKey(
            PaymentProviderType provider, String key);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select row from MockProviderPayment row where row.provider = :provider and"
                    + " row.providerIdempotencyKey = :key")
    Optional<MockProviderPayment> lockByKey(
            @Param("provider") PaymentProviderType provider, @Param("key") String key);
}
