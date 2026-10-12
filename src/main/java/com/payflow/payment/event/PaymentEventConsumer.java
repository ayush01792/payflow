package com.payflow.payment.event;

import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.service.PaymentLifecycleService;
import com.payflow.payment.service.PaymentOrchestrator;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private final PaymentLifecycleService lifecycle;
    private final PaymentOrchestrator orchestrator;

    public PaymentEventConsumer(
            PaymentLifecycleService lifecycle, PaymentOrchestrator orchestrator) {
        this.lifecycle = lifecycle;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            id = "paymentLifecycleListener",
            topics = PaymentEventTopics.EVENTS,
            groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, PaymentEvent> record) {
        PaymentEvent event = record.value();
        if (event == null
                || event.paymentId() == null
                || !event.paymentId().toString().equals(record.key())) {
            throw new InvalidPaymentEventException("Kafka key must match event payment ID");
        }
        log.info(
                "Received eventId={} paymentId={} type={} partition={}",
                event.eventId(),
                event.paymentId(),
                event.eventType(),
                record.partition());
        if (event.eventType() == PaymentEventType.PAYMENT_PROCESSING) orchestrator.handle(event);
        else lifecycle.handle(event);
    }
}
