package com.payflow.payment.service;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.entity.Payment;

public final class PaymentMapper {

    private PaymentMapper() {}

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency().getCurrencyCode(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
