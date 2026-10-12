package com.payflow.payment.mock;

import com.payflow.payment.provider.PaymentProviderType;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mock_provider_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockProviderPayment {
    @Id private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentProviderType provider;

    @Column(name = "provider_idempotency_key", nullable = false, length = 100)
    private String providerIdempotencyKey;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "call_count", nullable = false)
    private int callCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(precision = 19, scale = 4)
    private java.math.BigDecimal amount;

    @Column(length = 3)
    private String currency;

    public void details(java.math.BigDecimal amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public void receivedCall() {
        callCount++;
    }

    public void result(String result) {
        this.result = result;
    }
}
