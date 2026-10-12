package com.payflow.payment.repository;

import java.util.UUID;

import com.payflow.payment.entity.ProcessedKafkaEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedKafkaEventRepository extends JpaRepository<ProcessedKafkaEvent, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_kafka_events (event_id, event_type, processed_at)
            VALUES (:eventId, :eventType, CURRENT_TIMESTAMP)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("eventId") UUID eventId, @Param("eventType") String eventType);
}
