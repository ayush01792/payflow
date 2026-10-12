package com.payflow.payment.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
    private final OutboxPublisher publisher;

    public OutboxScheduler(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval-ms}")
    public void poll() {
        publisher.publishPending();
    }
}
