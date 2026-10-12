package com.payflow.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.entity.PaymentStatus;

public record PaymentResponse(UUID id, String merchantId, String userId, BigDecimal amount,
                              String currency, PaymentMethod paymentMethod, PaymentStatus status,
                              Instant createdAt, Instant updatedAt) {
}
