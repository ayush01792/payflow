package com.payflow.payment.provider;

public class ProviderWorkInProgressException extends RuntimeException {
    public ProviderWorkInProgressException() {
        super("Payment orchestration already claimed by another worker");
    }
}
