package com.payflow.payment.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.payflow.payment.entity.PaymentMethod;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

import java.util.Map;

class PayflowMetricsTest {
    @Test
    void businessCountersWaitForCommit() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PayflowMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            metrics.paymentEvent("PAYMENT_SUCCEEDED", PaymentMethod.UPI);
            assertThat(registry.find("payflow.payment.completed").counter()).isNull();
            var recording = TransactionSynchronizationManager.getSynchronizations().getFirst();
            recording.afterCommit();
            assertThat(registry.get("payflow.payment.completed").counter().count()).isEqualTo(1);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void snapshotKeepsLastKnownValuesAndExposesStalenessOnDatabaseFailure() {
        var registry = new SimpleMeterRegistry();
        var jdbc = mock(JdbcTemplate.class);
        var snapshot = new OperationalMetricsSnapshot(jdbc, registry);
        assertThat(snapshot.value("outbox.pending")).isNaN();
        when(jdbc.queryForMap(anyString()))
                .thenReturn(
                        Map.of(
                                "pending",
                                3L,
                                "failed",
                                1L,
                                "age",
                                90,
                                "payments",
                                2L,
                                "refunds",
                                1L,
                                "escalated",
                                1L));
        snapshot.refresh();
        assertThat(snapshot.value("outbox.pending")).isEqualTo(3);
        assertThat(registry.get("payflow.outbox.oldest.pending.age").gauge().value()).isEqualTo(90);
        when(jdbc.queryForMap(anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("test"));
        snapshot.refresh();
        assertThat(snapshot.value("outbox.pending")).isEqualTo(3);
        assertThat(registry.get("payflow.observability.snapshot.age").gauge().value())
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void metricRecordingFailureDoesNotThrowIntoBusinessTransaction() {
        var registry = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(registry.counter(anyString(), any(String[].class)))
                .thenThrow(new IllegalStateException("unavailable"));
        assertThatCode(
                        () ->
                                new PayflowMetrics(registry)
                                        .paymentEvent("PAYMENT_CREATED", PaymentMethod.UPI))
                .doesNotThrowAnyException();
    }
}
