package com.payflow.payment.exception;

public class WebhookRequestException extends RuntimeException {
    private final int status;

    public WebhookRequestException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
