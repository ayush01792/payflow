package com.payflow.payment.service;

import com.payflow.payment.dto.PaymentResponse;

/** Internal service result; the created flag is never part of payment JSON. */
public record PaymentCreationResult(PaymentResponse payment, boolean created) {
}
