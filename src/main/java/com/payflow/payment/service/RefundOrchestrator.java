package com.payflow.payment.service;

import com.payflow.payment.event.*;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.provider.*;
import com.payflow.payment.refund.*;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.retry.RetryRegistry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RefundOrchestrator {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RefundOrchestrator.class);
    private final RefundStore store;
    private final RefundLifecycleService lifecycle;
    private final PaymentProviderRouter providers;
    private final RetryRegistry retries;
    private final CircuitBreakerRegistry circuits;

    public RefundOrchestrator(
            RefundStore store,
            RefundLifecycleService lifecycle,
            PaymentProviderRouter providers,
            RetryRegistry retries,
            CircuitBreakerRegistry circuits) {
        this.store = store;
        this.lifecycle = lifecycle;
        this.providers = providers;
        this.retries = retries;
        this.circuits = circuits;
        for (PaymentProviderType provider : PaymentProviderType.values())
            circuits.circuitBreaker(provider.resilienceName() + "Refund")
                    .getEventPublisher()
                    .onStateTransition(
                            event ->
                                    log.info(
                                            "Refund circuit provider={} transition={}",
                                            provider,
                                            event.getStateTransition()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handle(RefundEvent event) {
        lifecycle.validate(event);
        if (event.eventType() != RefundEventType.REFUND_PROCESSING)
            throw new InvalidPaymentEventException(
                    "Refund orchestration requires PROCESSING event");
        var optional = store.claim(event);
        if (optional.isEmpty()) return;
        var claim = optional.get();
        try {
            var known = store.knownResult(claim.refundId());
            ProviderRefundResult result;
            if (known.isPresent()) result = known.get();
            else {
                AtomicBoolean uncertain = new AtomicBoolean(store.uncertain(claim.refundId()));
                try {
                    String instance = claim.provider().resilienceName() + "Refund";
                    result =
                            retries.retry(instance)
                                    .executeSupplier(
                                            () ->
                                                    circuits.circuitBreaker(instance)
                                                            .executeSupplier(
                                                                    () -> {
                                                                        var attempt =
                                                                                store.startAttempt(
                                                                                        claim);
                                                                        try {
                                                                            var response =
                                                                                    providers
                                                                                            .provider(
                                                                                                    claim
                                                                                                            .provider())
                                                                                            .refundPayment(
                                                                                                    claim
                                                                                                            .request());
                                                                            store.completeAttempt(
                                                                                    attempt,
                                                                                    response);
                                                                            return response;
                                                                        } catch (
                                                                                ProviderTechnicalException
                                                                                        failure) {
                                                                            store.failAttempt(
                                                                                    attempt,
                                                                                    failure);
                                                                            if (failure.ambiguous())
                                                                                uncertain.set(true);
                                                                            throw failure;
                                                                        }
                                                                    }));
                } catch (CallNotPermittedException open) {
                    store.finish(
                            claim,
                            null,
                            new ProviderTechnicalException(
                                    "PROVIDER_UNAVAILABLE", uncertain.get(), false));
                    return;
                } catch (ProviderTechnicalException failure) {
                    store.finish(
                            claim,
                            null,
                            uncertain.get()
                                    ? new ProviderTechnicalException(
                                            failure.failureCode(), true, false)
                                    : failure);
                    return;
                }
            }
            store.finish(claim, result, null);
        } catch (RuntimeException localFailure) {
            try {
                store.release(claim);
            } catch (RuntimeException releaseFailure) {
                localFailure.addSuppressed(releaseFailure);
            }
            throw localFailure;
        }
    }
}
