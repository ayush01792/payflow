package com.payflow.payment.refund;

import com.payflow.payment.service.RefundReconciliationService;

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
public class RefundReconciliationScheduler {
    private static final Logger log = LoggerFactory.getLogger(RefundReconciliationScheduler.class);
    private final RefundReconciliationService service;

    public RefundReconciliationScheduler(RefundReconciliationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${payflow.reconciliation.poll-interval-ms:5000}",
            initialDelayString = "${payflow.reconciliation.poll-interval-ms:5000}")
    public void poll() {
        try {
            service.reconcileBatch();
        } catch (RuntimeException failure) {
            log.error("Refund reconciliation batch failed; lease recovery is available", failure);
        }
    }
}
