package com.payflow.payment.exception;

public class RateLimitUnavailableException extends RuntimeException {

    public RateLimitUnavailableException() {
        super("Payment creation is temporarily unavailable");
    }
}
