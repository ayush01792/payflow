package com.payflow.payment.reconciliation;

import com.payflow.payment.provider.PaymentProviderType;

import java.util.UUID;

public record ReconciliationClaim(
        UUID paymentId,
        UUID token,
        UUID attemptId,
        int attemptNumber,
        PaymentProviderType provider,
        String providerReference) {}
