package com.payflow.payment.provider;

public record ProviderRefundResult(
        PaymentProviderType provider,
        String providerRefundId,
        Outcome outcome,
        String failureCode,
        String failureMessage) {
    public enum Outcome {
        SUCCESS,
        DECLINED,
        TECHNICAL_FAILURE,
        UNKNOWN
    }
}
