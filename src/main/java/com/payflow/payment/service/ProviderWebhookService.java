package com.payflow.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.dto.ProviderWebhookRequest;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.provider.*;
import com.payflow.payment.reconciliation.ReconciliationStore;
import com.payflow.payment.security.WebhookSignatureVerifier;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ProviderWebhookService {
    private final WebhookSignatureVerifier signatures;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final ReconciliationStore store;
    private final com.payflow.payment.refund.RefundReconciliationStore refundStore;

    public ProviderWebhookService(
            WebhookSignatureVerifier signatures,
            ObjectMapper mapper,
            Validator validator,
            ReconciliationStore store,
            com.payflow.payment.refund.RefundReconciliationStore refundStore) {
        this.signatures = signatures;
        this.mapper = mapper;
        this.validator = validator;
        this.store = store;
        this.refundStore = refundStore;
    }

    public void handle(PaymentProviderType provider, byte[] raw, String signature) {
        if (raw.length > 16384) throw new WebhookRequestException(413, "Webhook payload too large");
        signatures.verify(provider, raw, signature);
        ProviderWebhookRequest request;
        try {
            request = mapper.readValue(raw, ProviderWebhookRequest.class);
        } catch (java.io.IOException invalid) {
            throw new WebhookRequestException(400, "Invalid webhook payload");
        }
        if (request == null || !validator.validate(request).isEmpty())
            throw new WebhookRequestException(400, "Invalid webhook payload");
        ProviderStatusResult.Status status =
                switch (request.status()) {
                    case "SUCCESS", "REFUND_SUCCESS" -> ProviderStatusResult.Status.SUCCESS;
                    case "FAILED", "REFUND_FAILED" -> ProviderStatusResult.Status.FAILED;
                    case "PROCESSING", "REFUND_PROCESSING" ->
                            ProviderStatusResult.Status.PROCESSING;
                    default -> throw new WebhookRequestException(400, "Unsupported webhook status");
                };
        try {
            String hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            if (request.status().startsWith("REFUND_"))
                refundStore.webhook(provider, request, hash, status);
            else store.webhook(provider, request, hash, status);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
