package com.payflow.payment.provider;

import java.math.BigDecimal;

public record ProviderRefundStatusResult(
        PaymentProviderType provider,
        String providerReference,
        String providerRefundId,
        ProviderStatusResult.Status status,
        BigDecimal amount,
        String currency) {}
