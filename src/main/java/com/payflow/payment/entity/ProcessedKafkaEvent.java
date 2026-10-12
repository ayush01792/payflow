package com.payflow.payment.entity;

import java.time.Instant;
import java.util.UUID;

import com.payflow.payment.event.PaymentEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "processed_kafka_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedKafkaEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false, length = 30)
    private PaymentEventType eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;
}
