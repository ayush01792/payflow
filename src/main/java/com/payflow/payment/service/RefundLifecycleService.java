package com.payflow.payment.service;

import com.payflow.payment.entity.RefundStatus;
import com.payflow.payment.event.RefundEvent;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.refund.RefundStore;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;

@Service
public class RefundLifecycleService {
    private final RefundStore store;
    private final Validator validator;

    public RefundLifecycleService(RefundStore store, Validator validator) {
        this.store = store;
        this.validator = validator;
    }

    public void handle(RefundEvent event) {
        validate(event);
        store.lifecycle(event);
    }

    public void validate(RefundEvent event) {
        if (event == null
                || !validator.validate(event).isEmpty()
                || event.eventId().equals(event.refundId()))
            throw new InvalidPaymentEventException("Invalid refund event contract");
        RefundStatus expected =
                switch (event.eventType()) {
                    case REFUND_CREATED -> RefundStatus.CREATED;
                    case REFUND_PROCESSING -> RefundStatus.PROCESSING;
                    case REFUND_SUCCEEDED -> RefundStatus.SUCCESS;
                    case REFUND_FAILED -> RefundStatus.FAILED;
                    case REFUND_PENDING_CONFIRMATION -> RefundStatus.PENDING_CONFIRMATION;
                };
        if (event.status() != expected)
            throw new InvalidPaymentEventException("Refund event status mismatch");
    }
}
