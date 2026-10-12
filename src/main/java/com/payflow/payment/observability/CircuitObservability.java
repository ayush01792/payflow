package com.payflow.payment.observability;

import com.payflow.payment.provider.PaymentProviderType;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Component;

@Component
public class CircuitObservability {
    private final CircuitBreakerRegistry registry;
    private final PayflowMetrics metrics;

    public CircuitObservability(CircuitBreakerRegistry registry, PayflowMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
    }

    @PostConstruct
    void bind() {
        registry.getAllCircuitBreakers().forEach(this::bindOne);
        registry.getEventPublisher().onEntryAdded(event -> bindOne(event.getAddedEntry()));
    }

    private void bindOne(io.github.resilience4j.circuitbreaker.CircuitBreaker circuit) {
        if (!circuit.getName().matches("provider[AB](Refund)?(Status)?")) return;
        var provider =
                circuit.getName().startsWith("providerA")
                        ? PaymentProviderType.PROVIDER_A
                        : PaymentProviderType.PROVIDER_B;
        var operation =
                circuit.getName().endsWith("Status")
                        ? PayflowMetrics.Operation.STATUS_QUERY
                        : circuit.getName().contains("Refund")
                                ? PayflowMetrics.Operation.REFUND
                                : PayflowMetrics.Operation.PAYMENT;
        circuit.getEventPublisher()
                .onCallNotPermitted(
                        event ->
                                metrics.provider(
                                        provider, operation, PayflowMetrics.Outcome.CIRCUIT_OPEN));
    }
}
