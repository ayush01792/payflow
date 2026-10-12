package com.payflow.payment.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.payflow.payment.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query(value = "SELECT CAST(FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000000) AS BIGINT)", nativeQuery = true)
    long databaseEpochMicros();

    default Instant databaseNow() {
        long micros = databaseEpochMicros();
        return Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000);
    }

    // Only the earliest unpublished event of an aggregate is eligible. A FAILED head blocks successors.
    @Query(value = """
            SELECT candidate.* FROM outbox_events candidate
            WHERE ((candidate.status = 'PENDING' AND candidate.next_attempt_at <= CURRENT_TIMESTAMP)
                OR (candidate.status = 'CLAIMED' AND candidate.lease_until <= CURRENT_TIMESTAMP))
            AND NOT EXISTS (
                SELECT 1 FROM outbox_events earlier
                WHERE earlier.aggregate_type = candidate.aggregate_type
                  AND earlier.aggregate_id = candidate.aggregate_id
                  AND earlier.status <> 'PUBLISHED'
                  AND (earlier.created_at, earlier.id) < (candidate.created_at, candidate.id))
            ORDER BY candidate.created_at, candidate.id
            LIMIT 1 FOR UPDATE OF candidate SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxEvent> lockNextEligible();

    @Modifying
    @Query(value = """
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                claim_token = NULL, lease_until = NULL, last_error = NULL
            WHERE id = :id AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int markPublished(@Param("id") UUID id, @Param("token") UUID token);

    @Modifying
    @Query(value = """
            UPDATE outbox_events SET last_error = :error
            WHERE id = :id AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int recordUncertainSend(@Param("id") UUID id, @Param("token") UUID token, @Param("error") String error);

    @Modifying
    @Query(value = """
            UPDATE outbox_events SET status = :status, last_error = :error,
                next_attempt_at = CURRENT_TIMESTAMP + (:retryMillis * INTERVAL '1 millisecond'), claim_token = NULL, lease_until = NULL
            WHERE id = :id AND status = 'CLAIMED' AND claim_token = :token
            """, nativeQuery = true)
    int recordFailure(@Param("id") UUID id, @Param("token") UUID token,
                      @Param("status") String status, @Param("error") String error,
                      @Param("retryMillis") long retryMillis);
}
