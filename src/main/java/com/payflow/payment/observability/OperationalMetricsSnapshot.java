package com.payflow.payment.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OperationalMetricsSnapshot {
    private final JdbcTemplate jdbc;
    private volatile Map<String, Double> values = Map.of();

    public OperationalMetricsSnapshot(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String name :
                new String[] {
                    "outbox.pending",
                    "outbox.failed.count",
                    "outbox.oldest.pending.age",
                    "payment.pending.confirmation.count",
                    "refund.pending.confirmation.count",
                    "reconciliation.escalated.count"
                }) {
            var gauge = Gauge.builder("payflow." + name, this, s -> s.value(name));
            if (name.endsWith(".age")) gauge.baseUnit("seconds");
            gauge.register(registry);
        }
        Gauge.builder(
                        "payflow.observability.snapshot.age",
                        this,
                        s ->
                                s.refreshedAt == 0
                                        ? Double.NaN
                                        : (System.nanoTime() - s.refreshedAt) / 1e9)
                .baseUnit("seconds")
                .register(registry);
    }

    private volatile long refreshedAt;

    public double value(String name) {
        return values.getOrDefault(name, Double.NaN);
    }

    public void refresh() {
        try {
            var rows =
                    jdbc.queryForMap(
                            "SELECT (SELECT count(*) FROM outbox_events WHERE status IN"
                                + " ('PENDING','CLAIMED')) AS pending,(SELECT count(*) FROM"
                                + " outbox_events WHERE status='FAILED') AS failed,(SELECT"
                                + " coalesce(extract(epoch FROM"
                                + " clock_timestamp()-min(created_at)),0) FROM outbox_events WHERE"
                                + " status IN ('PENDING','CLAIMED')) AS age,(SELECT count(*) FROM"
                                + " payments WHERE status='PENDING_CONFIRMATION') AS"
                                + " payments,(SELECT count(*) FROM refunds WHERE"
                                + " status='PENDING_CONFIRMATION') AS refunds,(SELECT count(*) FROM"
                                + " payment_reconciliation_tasks t JOIN payments p ON"
                                + " p.id=t.payment_id WHERE t.escalated AND"
                                + " p.status='PENDING_CONFIRMATION')+(SELECT count(*) FROM"
                                + " refund_reconciliation_tasks t JOIN refunds r ON"
                                + " r.id=t.refund_id WHERE t.escalated AND"
                                + " r.status='PENDING_CONFIRMATION') AS escalated");
            values =
                    Map.of(
                            "outbox.pending",
                            number(rows, "pending"),
                            "outbox.failed.count",
                            number(rows, "failed"),
                            "outbox.oldest.pending.age",
                            Math.max(0, number(rows, "age")),
                            "payment.pending.confirmation.count",
                            number(rows, "payments"),
                            "refund.pending.confirmation.count",
                            number(rows, "refunds"),
                            "reconciliation.escalated.count",
                            number(rows, "escalated"));
            refreshedAt = System.nanoTime();
        } catch (org.springframework.dao.DataAccessException failure) {
            /* keep last snapshot; age exposes staleness */
        }
    }

    private double number(Map<String, Object> rows, String key) {
        return ((Number) rows.get(key)).doubleValue();
    }

    @Component
    @ConditionalOnProperty(
            name = "payflow.observability.snapshot-enabled",
            havingValue = "true",
            matchIfMissing = true)
    public static class RefreshTask {
        private final OperationalMetricsSnapshot snapshot;

        public RefreshTask(OperationalMetricsSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Scheduled(
                fixedDelayString = "${payflow.observability.snapshot-delay-ms:15000}",
                initialDelayString = "${payflow.observability.snapshot-delay-ms:15000}")
        public void refresh() {
            snapshot.refresh();
        }
    }
}
