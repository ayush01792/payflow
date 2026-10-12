package com.payflow.payment.provider;

public class ProviderRejectedException extends ProviderTechnicalException {
    public ProviderRejectedException() {
        super("PROVIDER_REQUEST_REJECTED", false, false);
    }
}
