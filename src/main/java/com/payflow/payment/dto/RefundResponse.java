package com.payflow.payment.dto;

import com.payflow.payment.entity.RefundStatus;
import com.payflow.payment.provider.PaymentProviderType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String merchantRefundReference,
        PaymentProviderType provider,
        String providerRefundId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt) {}
