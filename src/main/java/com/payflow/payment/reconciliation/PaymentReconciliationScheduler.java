package com.payflow.payment.reconciliation;

import com.payflow.payment.service.PaymentReconciliationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "payflow.reconciliation.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PaymentReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);
    private final PaymentReconciliationService service;

    public PaymentReconciliationScheduler(PaymentReconciliationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${payflow.reconciliation.poll-interval-ms:5000}",
            initialDelayString = "${payflow.reconciliation.poll-interval-ms:5000}")
    public void poll() {
        try {
            service.reconcileBatch();
        } catch (RuntimeException failure) {
            log.error("Reconciliation batch failed; leases permit recovery", failure);
        }
    }
}
