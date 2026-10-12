package com.payflow.payment.exception;

public class InvalidPaymentTransitionException extends RuntimeException {

    public InvalidPaymentTransitionException() {
        super("Invalid payment lifecycle transition");
    }
}
