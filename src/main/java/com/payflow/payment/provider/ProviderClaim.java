package com.payflow.payment.provider;

import java.util.UUID;

public record ProviderClaim(
        UUID paymentId,
        UUID eventId,
        UUID token,
        PaymentProviderType provider,
        ProviderPaymentRequest request) {}
