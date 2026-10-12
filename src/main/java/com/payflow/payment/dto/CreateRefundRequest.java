package com.payflow.payment.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreateRefundRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Size(max = 100) String merchantRefundReference) {}
