package com.payflow.payment.exception;

public class RefundConflictException extends RuntimeException {
    public RefundConflictException(String message) {
        super(message);
    }
}
