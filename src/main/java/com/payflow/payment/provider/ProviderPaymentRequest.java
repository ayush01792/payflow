package com.payflow.payment.provider;

import com.payflow.payment.entity.PaymentMethod;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public record ProviderPaymentRequest(
        @NotNull UUID paymentId,
        @NotBlank @Size(max = 100) String merchantId,
        @NotBlank @Size(max = 100) String userId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull PaymentMethod paymentMethod) {
    public String idempotencyKey(PaymentProviderType provider) {
        return "payflow:" + paymentId + ":" + provider.name();
    }
}
