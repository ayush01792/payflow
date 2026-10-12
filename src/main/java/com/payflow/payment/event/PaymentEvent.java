package com.payflow.payment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.entity.PaymentStatus;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentEvent(
        @NotNull UUID eventId,
        @NotNull PaymentEventType eventType,
        @Min(1) @Max(1) int eventVersion,
        @NotNull UUID paymentId,
        @NotBlank @Size(max = 100) String merchantId,
        @NotBlank @Size(max = 100) String userId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull PaymentMethod paymentMethod,
        @NotNull PaymentStatus status,
        @NotNull Instant occurredAt) {

    public static PaymentEvent forPayment(PaymentEventType type, PaymentResponse payment) {
        UUID eventId;
        do {
            eventId = UUID.randomUUID();
        } while (eventId.equals(payment.id()));
        return new PaymentEvent(eventId, type, 1, payment.id(), payment.merchantId(), payment.userId(),
                payment.amount(), payment.currency(), payment.paymentMethod(), payment.status(), Instant.now());
    }
}
