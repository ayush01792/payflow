package com.payflow.payment.provider;

public class RetryableProviderException extends ProviderTechnicalException {
    public RetryableProviderException(String code, boolean ambiguous, boolean safeToFailover) {
        super(code, ambiguous, safeToFailover);
    }
}
