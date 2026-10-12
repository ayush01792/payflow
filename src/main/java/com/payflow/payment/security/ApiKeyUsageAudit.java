package com.payflow.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ApiKeyUsageAudit {
    private final JdbcTemplate jdbc;

    private final long minimumIntervalSeconds;

    public ApiKeyUsageAudit(
            JdbcTemplate jdbc,
            @Value("${payflow.security.api-key-usage-audit-interval-seconds:60}")
                    long minimumIntervalSeconds) {
        if (minimumIntervalSeconds < 0) {
            throw new IllegalArgumentException("API key usage audit interval must be nonnegative");
        }
        this.jdbc = jdbc;
        this.minimumIntervalSeconds = minimumIntervalSeconds;
    }

    // Usage telemetry is best effort. PostgreSQL rechecks the predicate after a competing update,
    // so all replicas share one bounded write cadence without caching authentication decisions.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public void record(UUID id) {
        jdbc.update(
                "UPDATE merchant_api_keys SET last_used_at=now() WHERE id=? AND status='ACTIVE'"
                        + " AND (last_used_at IS NULL OR last_used_at <"
                        + " now() - (? * interval '1 second'))",
                id,
                minimumIntervalSeconds);
    }
}
