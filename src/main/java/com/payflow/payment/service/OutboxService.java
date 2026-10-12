package com.payflow.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.entity.OutboxEvent;
import com.payflow.payment.event.PaymentEvent;
import com.payflow.payment.observability.PayflowMetrics;
import com.payflow.payment.repository.OutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {
    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);
    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;

    private final PayflowMetrics metrics;

    public OutboxService(
            OutboxEventRepository outbox, ObjectMapper mapper, PayflowMetrics metrics) {
        this.outbox = outbox;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void savePaymentEvent(PaymentEvent event) {
        final String payload;
        try {
            payload = mapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize payment event for outbox", exception);
        }
        outbox.saveAndFlush(
                new OutboxEvent(
                        event.eventId(),
                        event.paymentId(),
                        event.eventType().name(),
                        event.eventVersion(),
                        payload,
                        outbox.databaseNow()));
        metrics.paymentEvent(event.eventType().name(), event.paymentMethod());
        log.info(
                "Outbox created outboxId={} eventId={} paymentId={} eventType={} attempt=0",
                event.eventId(),
                event.eventId(),
                event.paymentId(),
                event.eventType());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveRefundEvent(com.payflow.payment.event.RefundEvent event) {
        try {
            outbox.saveAndFlush(
                    OutboxEvent.refund(
                            event.eventId(),
                            event.refundId(),
                            event.eventType().name(),
                            event.eventVersion(),
                            mapper.writeValueAsString(event),
                            outbox.databaseNow()));
            metrics.refundEvent(event.eventType().name());
            log.info(
                    "Refund outbox intent eventId={} refundId={} eventType={}",
                    event.eventId(),
                    event.refundId(),
                    event.eventType());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize refund event for outbox", failure);
        }
    }
}
