package com.payflow.payment.repository;

import com.payflow.payment.entity.Refund;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
 @org.springframework.data.jpa.repository.Query("select r from Refund r, Payment p where r.id=:id and r.paymentId=p.id and p.merchantId=:merchantId")
 java.util.Optional<Refund> findOwned(@org.springframework.data.repository.query.Param("id") UUID id, @org.springframework.data.repository.query.Param("merchantId") String merchantId);
}
