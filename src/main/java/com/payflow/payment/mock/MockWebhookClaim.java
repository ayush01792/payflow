package com.payflow.payment.mock;

import com.payflow.payment.provider.PaymentProviderType;

import java.util.UUID;

public record MockWebhookClaim(
        UUID id, UUID token, PaymentProviderType provider, String payload, int attempt) {}
