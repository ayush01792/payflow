package com.payflow.payment.repository;

import java.util.UUID;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.payflow.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdAndMerchantId(UUID id, String merchantId);
    org.springframework.data.domain.Page<Payment> findAllByMerchantId(String merchantId, org.springframework.data.domain.Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :id and payment.merchantId = :merchantId")
    Optional<Payment> findOwnedForUpdate(@Param("id") UUID id, @Param("merchantId") String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from Payment payment where payment.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);
}
