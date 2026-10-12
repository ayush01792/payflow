package com.payflow.payment.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.provider.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class MockProviderStore {
    private final MockProviderPaymentRepository rows;
    private final ObjectMapper mapper;
    private final MockWebhookStore webhooks;

    public MockProviderStore(
            MockProviderPaymentRepository rows, ObjectMapper mapper, MockWebhookStore webhooks) {
        this.rows = rows;
        this.mapper = mapper;
        this.webhooks = webhooks;
    }

    @Transactional
    public MockPreparedResponse prepare(
            PaymentProviderType provider,
            ProviderPaymentRequest request,
            String key,
            MockBehavior behavior) {
        if (!request.idempotencyKey(provider).equals(key))
            return new MockPreparedResponse(400, null, 0);
        String hash = hash(request);
        rows.insertIfAbsent(UUID.randomUUID(), provider.name(), key, request.paymentId(), hash);
        MockProviderPayment row = rows.lockByKey(provider, key).orElseThrow();
        if (!row.getRequestHash().equals(hash)) return new MockPreparedResponse(409, null, 0);
        row.details(request.amount(), request.currency());
        row.receivedCall();
        if (row.getResult() == null) {
            int rejectStatus =
                    switch (behavior.scenario()) {
                        case HTTP_400 -> 400;
                        case HTTP_401 -> 401;
                        case HTTP_403 -> 403;
                        case HTTP_500 ->
                                behavior.failFirst() == 0
                                                || row.getCallCount() <= behavior.failFirst()
                                        ? 500
                                        : 200;
                        default -> 200;
                    };
            if (rejectStatus != 200) return new MockPreparedResponse(rejectStatus, null, 0);
            ProviderPaymentResult result =
                    (behavior.scenario() == MockScenario.DECLINE
                                    || behavior.scenario() == MockScenario.TIMEOUT_DECLINE)
                            ? new ProviderPaymentResult(
                                    provider,
                                    provider.name() + "-" + row.getId(),
                                    ProviderPaymentOutcome.DECLINED,
                                    "CARD_DECLINED",
                                    "Mock payment declined")
                            : new ProviderPaymentResult(
                                    provider,
                                    provider.name() + "-" + row.getId(),
                                    ProviderPaymentOutcome.SUCCESS,
                                    null,
                                    null);
            row.result(write(result));
            rows.flush();
            webhooks.schedule(row, request, result);
        }
        int delay =
                behavior.scenario() == MockScenario.TIMEOUT
                                || behavior.scenario() == MockScenario.TIMEOUT_DECLINE
                                || (behavior.scenario() == MockScenario.SLOW_SUCCESS
                                        && row.getCallCount() == 1)
                        ? behavior.delayMs()
                        : 0;
        return new MockPreparedResponse(200, read(row.getResult()), delay);
    }

    @Transactional(readOnly = true)
    public ProviderStatusResult status(
            PaymentProviderType provider, String key, MockStatusBehavior behavior) {
        var optional = rows.findByProviderAndProviderIdempotencyKey(provider, key);
        if (optional.isEmpty() || optional.get().getResult() == null)
            return new ProviderStatusResult(
                    provider, key, null, ProviderStatusResult.Status.UNKNOWN, null, null);
        var row = optional.get();
        var result = read(row.getResult());
        ProviderStatusResult.Status status =
                switch (behavior.mode()) {
                    case PROCESSING -> ProviderStatusResult.Status.PROCESSING;
                    case UNKNOWN -> ProviderStatusResult.Status.UNKNOWN;
                    default ->
                            result.outcome() == ProviderPaymentOutcome.SUCCESS
                                    ? ProviderStatusResult.Status.SUCCESS
                                    : ProviderStatusResult.Status.FAILED;
                };
        return new ProviderStatusResult(
                provider,
                key,
                result.providerTransactionId() == null
                        ? provider.name() + "-" + row.getId()
                        : result.providerTransactionId(),
                status,
                row.getAmount(),
                row.getCurrency());
    }

    private String hash(ProviderPaymentRequest request) {
        try {
            var normalized =
                    new ProviderPaymentRequest(
                            request.paymentId(),
                            request.merchantId(),
                            request.userId(),
                            request.amount().stripTrailingZeros(),
                            request.currency(),
                            request.paymentMethod());
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(write(normalized).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Mock result serialization failed", failure);
        }
    }

    private ProviderPaymentResult read(String value) {
        try {
            return mapper.readValue(value, ProviderPaymentResult.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Mock result decoding failed", failure);
        }
    }
}
