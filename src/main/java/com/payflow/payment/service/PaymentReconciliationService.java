package com.payflow.payment.service;

import com.payflow.payment.config.ReconciliationProperties;
import com.payflow.payment.provider.*;
import com.payflow.payment.reconciliation.ReconciliationStore;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.RetryRegistry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PaymentReconciliationService {
    private final ReconciliationStore store;
    private final PaymentProviderRouter router;
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry circuits;
    private final ReconciliationProperties properties;

    public PaymentReconciliationService(
            ReconciliationStore store,
            PaymentProviderRouter router,
            RetryRegistry retries,
            CircuitBreakerRegistry circuits,
            ReconciliationProperties properties) {
        this.store = store;
        this.router = router;
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
            ProviderStatusResult result = null;
            String error = null;
            try {
                String instance = claim.provider().resilienceName() + "Status";
                result =
                        retries.retry(instance)
                                .executeSupplier(
                                        () ->
                                                circuits.circuitBreaker(instance)
                                                        .executeSupplier(
                                                                () ->
                                                                        router.provider(
                                                                                        claim
                                                                                                .provider())
                                                                                .getPaymentStatus(
                                                                                        claim
                                                                                                .providerReference())));
            } catch (CallNotPermittedException open) {
                error = "PROVIDER_CIRCUIT_OPEN";
            } catch (ProviderTechnicalException unavailable) {
                error = unavailable.failureCode();
            }
            store.finish(claim, result, error);
        }
    }
}
