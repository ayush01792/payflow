package com.payflow.payment.provider;

/** Safe metadata only; no remote response body or broker/HTTP exception text is retained. */
public class ProviderTechnicalException extends RuntimeException {
    private final String failureCode;
    private final boolean ambiguous;
    private final boolean safeToFailover;

    public ProviderTechnicalException(
            String failureCode, boolean ambiguous, boolean safeToFailover) {
        super(failureCode);
        this.failureCode = failureCode;
        this.ambiguous = ambiguous;
        this.safeToFailover = safeToFailover && !ambiguous;
    }

    public String failureCode() {
        return failureCode;
    }

    public boolean ambiguous() {
        return ambiguous;
    }

    public boolean safeToFailover() {
        return safeToFailover;
    }
}
