package com.payflow.payment.controller;

import com.payflow.payment.provider.PaymentProviderType;
import com.payflow.payment.service.ProviderWebhookService;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/providers")
public class ProviderWebhookController {
    private final ProviderWebhookService service;

    public ProviderWebhookController(ProviderWebhookService service) {
        this.service = service;
    }

    @PostMapping(value = "/provider-a", consumes = "application/json")
    public Map<String, String> providerA(
            @RequestBody byte[] raw,
            @RequestHeader(value = "X-Payflow-Signature", required = false) String signature) {
        return accept(PaymentProviderType.PROVIDER_A, raw, signature);
    }

    @PostMapping(value = "/provider-b", consumes = "application/json")
    public Map<String, String> providerB(
            @RequestBody byte[] raw,
            @RequestHeader(value = "X-Payflow-Signature", required = false) String signature) {
        return accept(PaymentProviderType.PROVIDER_B, raw, signature);
    }

    private Map<String, String> accept(PaymentProviderType provider, byte[] raw, String signature) {
        service.handle(provider, raw, signature);
        return Map.of("status", "ACKNOWLEDGED");
    }
}
