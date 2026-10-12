package com.payflow.payment.event;

import com.payflow.payment.exception.InvalidPaymentEventException;
import com.payflow.payment.service.*;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RefundEventConsumer {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RefundEventConsumer.class);
    private final RefundLifecycleService lifecycle;
    private final RefundOrchestrator orchestrator;

    public RefundEventConsumer(RefundLifecycleService lifecycle, RefundOrchestrator orchestrator) {
        this.lifecycle = lifecycle;
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
            id = "refundLifecycleListener",
            topics = RefundEventTopics.EVENTS,
            groupId = "${spring.kafka.consumer.group-id}-refunds",
            containerFactory = "refundKafkaListenerContainerFactory")
    public void consume(ConsumerRecord<String, RefundEvent> record) {
        var event = record.value();
        if (event == null
                || event.refundId() == null
                || !event.refundId().toString().equals(record.key()))
            throw new InvalidPaymentEventException("Kafka key must match refund ID");
        log.info(
                "Received refund eventId={} refundId={} type={} partition={}",
                event.eventId(),
                event.refundId(),
                event.eventType(),
                record.partition());
        if (event.eventType() == RefundEventType.REFUND_PROCESSING) orchestrator.handle(event);
        else lifecycle.handle(event);
    }
}
