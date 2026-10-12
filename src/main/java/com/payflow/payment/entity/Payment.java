package com.payflow.payment.entity;

import com.payflow.payment.provider.PaymentProviderType;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Setter
    @NotBlank
    @Size(max = 100)
    @Column(name = "merchant_id", nullable = false, length = 100)
    private String merchantId;

    @Setter
    @NotBlank
    @Size(max = 100)
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Setter
    @NotNull
    @Positive
    @Digits(integer = 15, fraction = 4)
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Setter
    @NotNull
    @Convert(converter = CurrencyConverter.class)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Setter
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Setter
    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Payment(
            String merchantId,
            String userId,
            BigDecimal amount,
            Currency currency,
            PaymentMethod paymentMethod,
            PaymentStatus status) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.status = status;
    }

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentProviderType provider;

    @Setter
    @Column(name = "provider_transaction_id", length = 100)
    private String providerTransactionId;

    @Setter
    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "orchestration_provider", length = 20)
    private PaymentProviderType orchestrationProvider;

    @Setter
    @Column(name = "orchestration_token")
    private UUID orchestrationToken;

    @Setter
    @Column(name = "orchestration_lease_until")
    private Instant orchestrationLeaseUntil;

    @PrePersist
    private void onCreate() {
        // Match PostgreSQL's microsecond precision to avoid round-trip precision loss.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
