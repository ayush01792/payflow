package com.payflow.payment.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_idempotency", uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_idempotency_merchant_key", columnNames = {"merchant_id", "idempotency_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @NotBlank
    @Size(max = 100)
    @Column(name = "merchant_id", nullable = false, updatable = false, length = 100)
    private String merchantId;

    @NotBlank
    @Size(max = 128)
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @NotNull
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PaymentIdempotency(String merchantId, String idempotencyKey, String requestHash, UUID paymentId) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.paymentId = paymentId;
    }

    @PrePersist
    private void onCreate() {
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
