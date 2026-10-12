package com.payflow.payment.refund;

import com.payflow.payment.provider.PaymentProviderType;

import java.util.UUID;

public record RefundPollClaim(
        UUID refundId,
        UUID token,
        UUID attemptId,
        int attempt,
        PaymentProviderType provider,
        String reference) {}
