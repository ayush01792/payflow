package com.payflow.payment.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.config.WebhookProperties;
import com.payflow.payment.dto.ProviderWebhookRequest;
import com.payflow.payment.provider.*;
import com.payflow.payment.refund.RefundStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class MockRefundStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WebhookProperties webhooks;

    public MockRefundStore(JdbcTemplate jdbc, ObjectMapper mapper, WebhookProperties webhooks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.webhooks = webhooks;
    }

    @Transactional
    public MockRefundResponse prepare(
            PaymentProviderType provider,
            ProviderRefundRequest request,
            String key,
            MockBehavior behavior) {
        if (!key.equals(request.providerRefundIdempotencyKey())
                || !key.equals(RefundStore.key(request.refundId(), provider)))
            return new MockRefundResponse(400, null, 0);
        try {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    mapper.writeValueAsBytes(
                                                            new ProviderRefundRequest(
                                                                    request.refundId(),
                                                                    request.paymentId(),
                                                                    request.providerTransactionId(),
                                                                    request.amount()
                                                                            .stripTrailingZeros(),
                                                                    request.currency(),
                                                                    key))));
            // Lock the original captured transaction, coordinating all provider-side partial
            // refunds.
            var captures =
                    jdbc.queryForList(
                            "SELECT * FROM mock_provider_payments WHERE provider=? AND"
                                    + " provider_idempotency_key=? FOR UPDATE",
                            provider.name(),
                            "payflow:" + request.paymentId() + ":" + provider);
            if (captures.isEmpty()) return new MockRefundResponse(400, null, 0);
            var capture = captures.getFirst();
            var original =
                    mapper.readValue(
                            capture.get("result") == null
                                    ? "null"
                                    : capture.get("result").toString(),
                            ProviderPaymentResult.class);
            if (original == null
                    || original.outcome() != ProviderPaymentOutcome.SUCCESS
                    || !request.providerTransactionId().equals(original.providerTransactionId())
                    || !request.currency().equals(capture.get("currency")))
                return new MockRefundResponse(400, null, 0);
            UUID id = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO"
                        + " mock_provider_refunds(id,provider,provider_idempotency_key,refund_id,payment_id,provider_transaction_id,amount,currency,request_hash)"
                        + " VALUES (?,?,?,?,?,?,?,?,?) ON"
                        + " CONFLICT(provider,provider_idempotency_key) DO NOTHING",
                    id,
                    provider.name(),
                    key,
                    request.refundId(),
                    request.paymentId(),
                    request.providerTransactionId(),
                    request.amount(),
                    request.currency(),
                    hash);
            var row =
                    jdbc.queryForMap(
                            "SELECT * FROM mock_provider_refunds WHERE provider=? AND"
                                    + " provider_idempotency_key=? FOR UPDATE",
                            provider.name(),
                            key);
            if (!hash.equals(row.get("request_hash"))) return new MockRefundResponse(409, null, 0);
            id = (UUID) row.get("id");
            int calls = ((Number) row.get("call_count")).intValue() + 1;
            jdbc.update("UPDATE mock_provider_refunds SET call_count=? WHERE id=?", calls, id);
            ProviderRefundResult result;
            if (row.get("result") == null) {
                int rejected =
                        switch (behavior.scenario()) {
                            case HTTP_500 ->
                                    behavior.failFirst() == 0 || calls <= behavior.failFirst()
                                            ? 503
                                            : 200;
                            case HTTP_400 -> 400;
                            case HTTP_401 -> 401;
                            case HTTP_403 -> 403;
                            default -> 200;
                        };
                if (rejected != 200) return new MockRefundResponse(rejected, null, 0);
                BigDecimal used =
                        jdbc.queryForObject(
                                "SELECT coalesce(sum(amount),0) FROM mock_provider_refunds WHERE"
                                        + " provider=? AND provider_transaction_id=? AND"
                                        + " result->>'outcome'='SUCCESS'",
                                BigDecimal.class,
                                provider.name(),
                                request.providerTransactionId());
                boolean declined =
                        behavior.scenario() == MockScenario.DECLINE
                                || behavior.scenario() == MockScenario.TIMEOUT_DECLINE
                                || used.add(request.amount())
                                                .compareTo((BigDecimal) capture.get("amount"))
                                        > 0;
                result =
                        new ProviderRefundResult(
                                provider,
                                provider + "-REFUND-" + id,
                                declined
                                        ? ProviderRefundResult.Outcome.DECLINED
                                        : ProviderRefundResult.Outcome.SUCCESS,
                                declined ? "REFUND_DECLINED" : null,
                                declined ? "Mock refund declined" : null);
                jdbc.update(
                        "UPDATE mock_provider_refunds SET result=?::jsonb WHERE id=?",
                        mapper.writeValueAsString(result),
                        id);
                if (webhooks.isMockDeliveryEnabled()) {
                    var body =
                            new ProviderWebhookRequest(
                                    "mock-refund-" + id,
                                    result.providerRefundId(),
                                    key,
                                    declined ? "REFUND_FAILED" : "REFUND_SUCCESS",
                                    request.amount(),
                                    request.currency(),
                                    Instant.now());
                    jdbc.update(
                            "INSERT INTO"
                                + " mock_provider_webhook_deliveries(id,refund_ledger_id,provider,payload,next_attempt_at)"
                                + " VALUES (?,?,?,?,clock_timestamp()+(? * interval '1"
                                + " millisecond')) ON CONFLICT(id) DO NOTHING",
                            id,
                            id,
                            provider.name(),
                            mapper.writeValueAsString(body),
                            webhooks.getMockDelayMs());
                }
            } else
                result = mapper.readValue(row.get("result").toString(), ProviderRefundResult.class);
            int delay =
                    behavior.scenario() == MockScenario.TIMEOUT
                                    || behavior.scenario() == MockScenario.TIMEOUT_DECLINE
                                    || (behavior.scenario() == MockScenario.SLOW_SUCCESS
                                            && calls == 1)
                            ? behavior.delayMs()
                            : 0;
            return new MockRefundResponse(200, result, delay);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Mock refund persistence failed", failure);
        }
    }

    @Transactional(readOnly = true)
    public ProviderRefundStatusResult status(
            PaymentProviderType provider, String key, MockStatusBehavior behavior) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM mock_provider_refunds WHERE provider=? AND"
                                + " provider_idempotency_key=?",
                        provider.name(),
                        key);
        if (rows.isEmpty() || rows.getFirst().get("result") == null)
            return new ProviderRefundStatusResult(
                    provider, key, null, ProviderStatusResult.Status.UNKNOWN, null, null);
        var row = rows.getFirst();
        try {
            var result = mapper.readValue(row.get("result").toString(), ProviderRefundResult.class);
            var status =
                    switch (behavior.mode()) {
                        case UNKNOWN -> ProviderStatusResult.Status.UNKNOWN;
                        case PROCESSING -> ProviderStatusResult.Status.PROCESSING;
                        default ->
                                result.outcome() == ProviderRefundResult.Outcome.SUCCESS
                                        ? ProviderStatusResult.Status.SUCCESS
                                        : ProviderStatusResult.Status.FAILED;
                    };
            return new ProviderRefundStatusResult(
                    provider,
                    key,
                    result.providerRefundId(),
                    status,
                    (BigDecimal) row.get("amount"),
                    (String) row.get("currency"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
