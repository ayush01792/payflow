package com.payflow.payment.service;

import com.payflow.payment.config.ReconciliationProperties;
import com.payflow.payment.provider.*;
import com.payflow.payment.refund.RefundReconciliationStore;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.RetryRegistry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RefundReconciliationService {
    private final RefundReconciliationStore store;
    private final PaymentProviderRouter providers;
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry circuits;
    private final ReconciliationProperties properties;

    public RefundReconciliationService(
            RefundReconciliationStore store,
            PaymentProviderRouter providers,
            RetryRegistry retries,
            CircuitBreakerRegistry circuits,
            ReconciliationProperties properties) {
        this.store = store;
        this.providers = providers;
        this.retries = retries;
        this.circuits = circuits;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reconcileBatch() {
        for (int i = 0; i < properties.getBatchSize(); i++) {
            var optional = store.claim();
            if (optional.isEmpty()) return;
            var claim = optional.get();
            ProviderRefundStatusResult result = null;
            String error = null;
            try {
                String name = claim.provider().resilienceName() + "RefundStatus";
                result =
                        retries.retry(name)
                                .executeSupplier(
                                        () ->
                                                circuits.circuitBreaker(name)
                                                        .executeSupplier(
                                                                () ->
                                                                        providers
                                                                                .provider(
                                                                                        claim
                                                                                                .provider())
                                                                                .getRefundStatus(
                                                                                        claim
                                                                                                .reference())));
            } catch (CallNotPermittedException open) {
                error = "PROVIDER_CIRCUIT_OPEN";
            } catch (ProviderTechnicalException unavailable) {
                error = unavailable.failureCode();
            }
            store.finish(claim, result, error);
        }
    }
}
