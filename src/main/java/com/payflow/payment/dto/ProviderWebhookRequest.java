package com.payflow.payment.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;

public record ProviderWebhookRequest(
        @NotBlank @Size(max = 100) String eventId,
        @NotBlank @Size(max = 100) String providerTransactionId,
        @NotBlank @Size(max = 100) String providerReference,
        @NotBlank @Size(max = 20) String status,
        @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull Instant occurredAt) {}
