package com.payflow.payment.provider;

public interface PaymentProvider {
    PaymentProviderType type();

    ProviderPaymentResult processPayment(ProviderPaymentRequest request);

    ProviderStatusResult getPaymentStatus(String providerReference);

    ProviderRefundResult refundPayment(ProviderRefundRequest request);

    ProviderRefundStatusResult getRefundStatus(String providerReference);
}
