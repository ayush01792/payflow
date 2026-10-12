package com.payflow.payment.provider;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.UUID;

public record ProviderRefundRequest(
        @NotNull UUID refundId,
        @NotNull UUID paymentId,
        @NotBlank @Size(max = 100) String providerTransactionId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 100) String providerRefundIdempotencyKey) {}
