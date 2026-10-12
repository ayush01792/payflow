package com.payflow.payment.mock;

import com.payflow.payment.provider.PaymentProviderType;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo scenario settings only; all idempotency/results and request counters are persisted in
 * PostgreSQL.
 */
@Component
public class MockProviderControl {
    private final Map<PaymentProviderType, MockBehavior> settings = new ConcurrentHashMap<>();

    public MockBehavior behavior(PaymentProviderType provider) {
        return settings.getOrDefault(provider, MockBehavior.success());
    }

    public void configure(PaymentProviderType provider, MockBehavior behavior) {
        settings.put(provider, behavior);
    }

    private final Map<PaymentProviderType, MockStatusBehavior> statusSettings =
            new ConcurrentHashMap<>();

    public MockStatusBehavior statusBehavior(PaymentProviderType provider) {
        return statusSettings.getOrDefault(provider, MockStatusBehavior.normal());
    }

    public void configureStatus(PaymentProviderType provider, MockStatusBehavior behavior) {
        statusSettings.put(provider, behavior);
    }

    private final Map<PaymentProviderType, MockBehavior> refundSettings = new ConcurrentHashMap<>();
    private final Map<PaymentProviderType, MockStatusBehavior> refundStatusSettings =
            new ConcurrentHashMap<>();

    public MockBehavior refundBehavior(PaymentProviderType provider) {
        return refundSettings.getOrDefault(provider, MockBehavior.success());
    }

    public void configureRefund(PaymentProviderType provider, MockBehavior behavior) {
        refundSettings.put(provider, behavior);
    }

    public MockStatusBehavior refundStatusBehavior(PaymentProviderType provider) {
        return refundStatusSettings.getOrDefault(provider, MockStatusBehavior.normal());
    }

    public void configureRefundStatus(PaymentProviderType provider, MockStatusBehavior behavior) {
        refundStatusSettings.put(provider, behavior);
    }

    public void reset() {
        refundSettings.clear();
        refundStatusSettings.clear();
        statusSettings.clear();
        settings.clear();
    }
}
