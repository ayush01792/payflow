package com.payflow.payment.entity;

public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCESS,
    PENDING_CONFIRMATION,
    PARTIALLY_REFUNDED,
    REFUNDED,
    FAILED
}
