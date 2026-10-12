package com.payflow.payment.dto;

import java.math.BigDecimal;

import com.payflow.payment.entity.PaymentMethod;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreatePaymentRequest(
        @NotBlank @Size(max = 100) String userId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter uppercase currency code")
        String currency,
        @NotNull PaymentMethod paymentMethod) {
}
