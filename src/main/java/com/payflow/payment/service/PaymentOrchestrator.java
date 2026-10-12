package com.payflow.payment.service;

import com.payflow.payment.config.ProviderProperties;
import com.payflow.payment.event.PaymentEvent;
import com.payflow.payment.event.PaymentEventType;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.provider.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);
    private final PaymentLifecycleService lifecycle;
    private final ProviderWorkflowStore store;
    private final ProviderInvocationService invocation;
    private final PaymentProviderRouter router;
    private final ProviderProperties properties;

    public PaymentOrchestrator(
            PaymentLifecycleService lifecycle,
            ProviderWorkflowStore store,
            ProviderInvocationService invocation,
            PaymentProviderRouter router,
            ProviderProperties properties) {
        this.lifecycle = lifecycle;
        this.store = store;
        this.invocation = invocation;
        this.router = router;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void handle(PaymentEvent event) {
        if (!properties.isEnabled()) {
            lifecycle.handle(event);
            return;
        }
        lifecycle.validate(event);
        if (event.eventType() != PaymentEventType.PAYMENT_PROCESSING)
            throw new InvalidPaymentEventException("Provider requires PAYMENT_PROCESSING event");
        var optional = store.claim(event);
        if (optional.isEmpty()) return;
        ProviderClaim claim = optional.get();
        PaymentProvider provider = router.provider(claim.provider());
        try {
            ProviderPaymentResult result;
            try {
                result = invocation.invoke(claim, provider);
            } catch (ProviderTechnicalException primaryFailure) {
                if (!primaryFailure.safeToFailover()) {
                    store.finishTechnical(claim, provider.type(), primaryFailure);
                    return;
                }
                PaymentProvider fallback = router.fallback(provider.type());
                log.info(
                        "Provider failover paymentId={} primary={} fallback={}",
                        claim.paymentId(),
                        provider.type(),
                        fallback.type());
                provider = fallback;
                try {
                    result = invocation.invoke(claim, provider);
                } catch (ProviderTechnicalException fallbackFailure) {
                    store.finishTechnical(claim, provider.type(), fallbackFailure);
                    return;
                }
            }
            store.finishResult(claim, result);
        } catch (RuntimeException localFailure) {
            // Network result may already be durable at the provider. Retain provider/key for safe
            // source-event retry.
            try {
                store.releaseAfterLocalFailure(claim);
            } catch (RuntimeException releaseFailure) {
                localFailure.addSuppressed(releaseFailure);
            }
            throw localFailure;
        }
    }
}
