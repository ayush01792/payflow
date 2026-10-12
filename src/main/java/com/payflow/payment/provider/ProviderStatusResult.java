package com.payflow.payment.provider;

import java.math.BigDecimal;

/** Explicit remote status vocabulary, never an internal PaymentStatus. */
public record ProviderStatusResult(
        PaymentProviderType provider,
        String providerReference,
        String providerTransactionId,
        Status status,
        BigDecimal amount,
        String currency) {
    public enum Status {
        SUCCESS,
        FAILED,
        PROCESSING,
        UNKNOWN
    }
}
