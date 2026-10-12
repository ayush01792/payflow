package com.payflow.payment.service;

import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.event.PaymentEvent;
import com.payflow.payment.event.PaymentEventType;
import com.payflow.payment.event.PaymentStatusChanged;
import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.exception.InvalidPaymentTransitionException;
import com.payflow.payment.exception.PaymentNotFoundException;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.ProcessedKafkaEventRepository;

import jakarta.validation.Validator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

@Service
public class PaymentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PaymentLifecycleService.class);
    private final PaymentRepository payments;
    private final ProcessedKafkaEventRepository processed;
    private final ApplicationEventPublisher events;
    private final OutboxService outbox;
    private final Validator validator;

    public PaymentLifecycleService(
            PaymentRepository payments,
            ProcessedKafkaEventRepository processed,
            ApplicationEventPublisher events,
            Validator validator,
            OutboxService outbox) {
        this.payments = payments;
        this.processed = processed;
        this.events = events;
        this.validator = validator;
        this.outbox = outbox;
    }

    @Transactional
    public void handle(PaymentEvent event) {
        validate(event);
        // Claim and state change share a transaction. Concurrent duplicate IDs wait here.
        if (processed.claim(event.eventId(), event.eventType().name()) == 0) {
            log.debug(
                    "Skipping duplicate eventId={} paymentId={}",
                    event.eventId(),
                    event.paymentId());
            return;
        }
        switch (event.eventType()) {
            case PAYMENT_CREATED -> transitionPaymentStatus(event);
            case PAYMENT_PROCESSING, PAYMENT_SUCCEEDED, PAYMENT_FAILED ->
                    log.debug(
                            "Recorded lifecycle notification eventId={} paymentId={} type={}",
                            event.eventId(),
                            event.paymentId(),
                            event.eventType());
        }
    }

    private void transitionPaymentStatus(PaymentEvent event) {
        Payment payment =
                payments.findByIdForUpdate(event.paymentId())
                        .orElseThrow(() -> new PaymentNotFoundException(event.paymentId()));
        if (!payment.getMerchantId().equals(event.merchantId())
                || !payment.getUserId().equals(event.userId())
                || payment.getAmount().compareTo(event.amount()) != 0
                || !payment.getCurrency().getCurrencyCode().equals(event.currency())
                || payment.getPaymentMethod() != event.paymentMethod()) {
            throw new InvalidPaymentEventException("Event does not match the persisted payment");
        }
        if (payment.getStatus() == PaymentStatus.PROCESSING) {
            return; // A different CREATED event ID must not repeat the transition or publish again.
        }
        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw new InvalidPaymentTransitionException();
        }
        payment.setStatus(PaymentStatus.PROCESSING);
        payments.flush();
        PaymentEvent processing =
                PaymentEvent.forPayment(
                        PaymentEventType.PAYMENT_PROCESSING, PaymentMapper.toResponse(payment));
        outbox.savePaymentEvent(processing);
        // Local after-commit event is now exclusively for cache eviction, never Kafka publication.
        events.publishEvent(new PaymentStatusChanged(payment.getId()));
    }

    public void validate(PaymentEvent event) {
        if (event == null || !validator.validate(event).isEmpty()) {
            throw new InvalidPaymentEventException("Invalid payment event contract");
        }
        if (event.eventId().equals(event.paymentId())) {
            throw new InvalidPaymentEventException("Event ID must differ from payment ID");
        }
        PaymentStatus expected =
                switch (event.eventType()) {
                    case PAYMENT_CREATED -> PaymentStatus.CREATED;
                    case PAYMENT_PROCESSING -> PaymentStatus.PROCESSING;
                    case PAYMENT_SUCCEEDED -> PaymentStatus.SUCCESS;
                    case PAYMENT_FAILED -> PaymentStatus.FAILED;
                };
        if (event.status() != expected) {
            throw new InvalidPaymentEventException("Event type and status do not match");
        }
        try {
            Currency.getInstance(event.currency());
        } catch (IllegalArgumentException exception) {
            throw new InvalidPaymentEventException("Invalid event currency");
        }
    }
}
