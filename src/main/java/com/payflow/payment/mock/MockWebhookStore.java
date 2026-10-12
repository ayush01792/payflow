package com.payflow.payment.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.WebhookProperties;
import com.payflow.payment.dto.ProviderWebhookRequest;
import com.payflow.payment.provider.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class MockWebhookStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookProperties properties;

    public MockWebhookStore(JdbcTemplate jdbc, ObjectMapper mapper, WebhookProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void schedule(
            MockProviderPayment row, ProviderPaymentRequest request, ProviderPaymentResult result) {
        if (!properties.isMockDeliveryEnabled()) return;
        try {
            ProviderWebhookRequest body =
                    new ProviderWebhookRequest(
                            "mock-" + row.getId(),
                            result.providerTransactionId(),
                            request.idempotencyKey(row.getProvider()),
                            result.outcome() == ProviderPaymentOutcome.SUCCESS
                                    ? "SUCCESS"
                                    : "FAILED",
                            request.amount(),
                            request.currency(),
                            Instant.now());
            jdbc.update(
                    "INSERT INTO"
                        + " mock_provider_webhook_deliveries(id,payment_ledger_id,provider,payload,next_attempt_at)"
                        + " VALUES (?,?,?,?,clock_timestamp()+(? * interval '1 millisecond')) ON"
                        + " CONFLICT(id) DO NOTHING",
                    row.getId(),
                    row.getId(),
                    row.getProvider().name(),
                    mapper.writeValueAsString(body),
                    properties.getMockDelayMs());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Mock webhook serialization failed", failure);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<MockWebhookClaim> claim() {
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                    + " status='FAILED',owner_token=NULL,lease_until=NULL WHERE status='PENDING'"
                    + " AND attempt_count>=? AND (lease_until IS NULL OR"
                    + " lease_until<=clock_timestamp())",
                properties.getMaxAttempts());
        List<MockWebhookClaim> rows =
                jdbc.query(
                        "SELECT id,provider,payload,attempt_count FROM"
                            + " mock_provider_webhook_deliveries WHERE status='PENDING' AND"
                            + " attempt_count<? AND next_attempt_at<=clock_timestamp() AND"
                            + " (lease_until IS NULL OR lease_until<=clock_timestamp()) ORDER BY"
                            + " next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",
                        (rs, n) ->
                                new MockWebhookClaim(
                                        rs.getObject(1, UUID.class),
                                        UUID.randomUUID(),
                                        PaymentProviderType.valueOf(rs.getString(2)),
                                        rs.getString(3),
                                        rs.getInt(4) + 1),
                        properties.getMaxAttempts());
        if (rows.isEmpty()) return Optional.empty();
        var claim = rows.getFirst();
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                        + " owner_token=?,lease_until=clock_timestamp()+interval '30"
                        + " seconds',attempt_count=? WHERE id=?",
                claim.token(),
                claim.attempt(),
                claim.id());
        return Optional.of(claim);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(MockWebhookClaim claim, int httpStatus) {
        boolean success = httpStatus >= 200 && httpStatus < 300;
        boolean retry =
                (httpStatus == 0 || httpStatus >= 500)
                        && claim.attempt() < properties.getMaxAttempts();
        jdbc.update(
                "UPDATE mock_provider_webhook_deliveries SET"
                    + " status=?,owner_token=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+(?"
                    + " * interval '1 millisecond') WHERE id=? AND owner_token=? AND"
                    + " lease_until>clock_timestamp()",
                success ? "DELIVERED" : retry ? "PENDING" : "FAILED",
                properties.getRetryDelayMs() * claim.attempt(),
                claim.id(),
                claim.token());
    }
}
