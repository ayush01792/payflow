package com.payflow.payment.event;

import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.entity.RefundStatus;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundEvent(
        @NotNull UUID eventId,
        @Min(1) @Max(1) int eventVersion,
        @NotNull RefundEventType eventType,
        @NotNull UUID refundId,
        @NotNull UUID paymentId,
        @NotBlank @Size(max = 100) String merchantId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull RefundStatus status,
        @NotNull Instant occurredAt) {
    public static RefundEvent forRefund(
            RefundEventType type, RefundResponse refund, String merchant) {
        return new RefundEvent(
                UUID.randomUUID(),
                1,
                type,
                refund.id(),
                refund.paymentId(),
                merchant,
                refund.amount(),
                refund.currency(),
                refund.status(),
                Instant.now());
    }
}
