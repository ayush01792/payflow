package com.payflow.payment.provider;

public enum PaymentProviderType {
    PROVIDER_A("providerA", "provider-a"),
    PROVIDER_B("providerB", "provider-b");
    private final String resilienceName;
    private final String mockPath;

    PaymentProviderType(String resilienceName, String mockPath) {
        this.resilienceName = resilienceName;
        this.mockPath = mockPath;
    }

    public String resilienceName() {
        return resilienceName;
    }

    public String mockPath() {
        return mockPath;
    }
}
