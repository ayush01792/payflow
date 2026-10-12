package com.payflow.payment.refund;

import com.payflow.payment.provider.PaymentProviderType;
import com.payflow.payment.provider.ProviderRefundRequest;

import java.util.UUID;

public record RefundClaim(
        UUID refundId,
        UUID eventId,
        UUID token,
        PaymentProviderType provider,
        ProviderRefundRequest request) {}
