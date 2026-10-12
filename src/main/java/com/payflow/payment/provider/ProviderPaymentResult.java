package com.payflow.payment.provider;

public record ProviderPaymentResult(
        PaymentProviderType providerName,
        String providerTransactionId,
        ProviderPaymentOutcome outcome,
        String failureCode,
        String failureMessage) {}
