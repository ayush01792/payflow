package com.payflow.payment.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Low-level Kafka transport used only by the outbox worker; failure is reported to its caller. */
@Component
public class PaymentEventPublisher {
    private final KafkaTemplate<String, Object> kafka;

    public PaymentEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public CompletableFuture<SendResult<String, Object>> publish(
            UUID paymentId, String storedJson) {
        // Preserve the stored snapshot, including eventId; do not serialize or reconstruct it
        // again.
        return kafka.send(
                PaymentEventTopics.EVENTS,
                paymentId.toString(),
                storedJson.getBytes(StandardCharsets.UTF_8));
    }

    public CompletableFuture<SendResult<String, Object>> publishRefund(
            UUID refundId, String storedJson) {
        return kafka.send(
                RefundEventTopics.EVENTS,
                refundId.toString(),
                storedJson.getBytes(StandardCharsets.UTF_8));
    }
}
