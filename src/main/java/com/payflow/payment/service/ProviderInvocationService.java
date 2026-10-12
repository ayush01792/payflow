package com.payflow.payment.service;

import com.payflow.payment.provider.*;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ProviderInvocationService {
    private static final Logger log = LoggerFactory.getLogger(ProviderInvocationService.class);
    private final ProviderWorkflowStore store;
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry circuits;

    public ProviderInvocationService(
            ProviderWorkflowStore store, RetryRegistry retries, CircuitBreakerRegistry circuits) {
        this.store = store;
        this.retries = retries;
        this.circuits = circuits;
        for (PaymentProviderType provider : PaymentProviderType.values()) {
            circuits.circuitBreaker(provider.resilienceName())
                    .getEventPublisher()
                    .onStateTransition(
                            event ->
                                    log.info(
                                            "Provider circuit provider={} transition={}",
                                            provider,
                                            event.getStateTransition()));
        }
    }

    public ProviderPaymentResult invoke(ProviderClaim claim, PaymentProvider provider) {
        var known = store.knownBusinessResult(claim.paymentId(), provider.type());
        if (known.isPresent()) {
            log.info(
                    "Recovering provider result paymentId={} provider={} outcome={}",
                    claim.paymentId(),
                    provider.type(),
                    known.get().outcome());
            return known.get();
        }
        AtomicBoolean uncertain =
                new AtomicBoolean(store.hasUncertainAttempts(claim.paymentId(), provider.type()));
        try {
            // Explicit composition: Retry outside CircuitBreaker. Each permitted physical HTTP call
            // gets an audit row.
            return retries.retry(provider.type().resilienceName())
                    .executeSupplier(
                            () ->
                                    circuits.circuitBreaker(provider.type().resilienceName())
                                            .executeSupplier(
                                                    () ->
                                                            physicalCall(
                                                                    claim, provider, uncertain)));
        } catch (CallNotPermittedException failure) {
            throw new ProviderTechnicalException(
                    "PROVIDER_UNAVAILABLE", uncertain.get(), !uncertain.get());
        } catch (ProviderTechnicalException failure) {
            if (uncertain.get())
                throw new ProviderTechnicalException(failure.failureCode(), true, false);
            throw failure;
        }
    }

    private ProviderPaymentResult physicalCall(
            ProviderClaim claim, PaymentProvider provider, AtomicBoolean uncertain) {
        var attempt = store.startAttempt(claim, provider.type());
        log.debug(
                "Provider call paymentId={} provider={} attempt={} retry={}",
                claim.paymentId(),
                provider.type(),
                attempt.getAttemptNumber(),
                attempt.getAttemptNumber() > 1);
        try {
            ProviderPaymentResult result = provider.processPayment(claim.request());
            store.completeAttempt(attempt.getId(), result);
            log.info(
                    "Provider result paymentId={} provider={} attempt={} outcome={}"
                            + " providerTransactionId={}",
                    claim.paymentId(),
                    provider.type(),
                    attempt.getAttemptNumber(),
                    result.outcome(),
                    result.providerTransactionId());
            return result;
        } catch (ProviderTechnicalException failure) {
            uncertain.compareAndSet(false, failure.ambiguous());
            store.failedAttempt(attempt.getId(), failure);
            log.warn(
                    "Provider technical failure paymentId={} provider={} attempt={} outcome={}"
                            + " ambiguous={}",
                    claim.paymentId(),
                    provider.type(),
                    attempt.getAttemptNumber(),
                    failure.failureCode(),
                    failure.ambiguous());
            throw failure;
        }
    }
}
