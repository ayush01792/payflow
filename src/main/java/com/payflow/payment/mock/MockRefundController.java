package com.payflow.payment.mock;

import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.provider.*;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mock/providers/{providerPath}")
@ConditionalOnProperty(
        name = "payflow.mock-providers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MockRefundController {
    private final MockRefundStore store;
    private final MockProviderControl controls;

    public MockRefundController(MockRefundStore store, MockProviderControl controls) {
        this.store = store;
        this.controls = controls;
    }

    private PaymentProviderType provider(String path) {
        return switch (path) {
            case "provider-a" -> PaymentProviderType.PROVIDER_A;
            case "provider-b" -> PaymentProviderType.PROVIDER_B;
            default -> throw new WebhookRequestException(404, "Provider not found");
        };
    }

    @PostMapping("/refunds")
    public ResponseEntity<ProviderRefundResult> refund(
            @PathVariable String providerPath,
            @Valid @RequestBody ProviderRefundRequest request,
            @RequestHeader("Idempotency-Key") String key)
            throws InterruptedException {
        var provider = provider(providerPath);
        var response = store.prepare(provider, request, key, controls.refundBehavior(provider));
        if (response.delayMs() > 0) Thread.sleep(response.delayMs());
        return ResponseEntity.status(response.status())
                .header(
                        "X-Mock-Not-Processed",
                        Boolean.toString(response.status() != 200 && response.status() != 409))
                .body(response.result());
    }

    @GetMapping("/refunds/{reference}")
    public ResponseEntity<ProviderRefundStatusResult> status(
            @PathVariable String providerPath, @PathVariable String reference)
            throws InterruptedException {
        var provider = provider(providerPath);
        var behavior = controls.refundStatusBehavior(provider);
        if (behavior.mode() == MockStatusBehavior.Mode.HTTP_503)
            return ResponseEntity.status(503).build();
        var response = store.status(provider, reference, behavior);
        if (behavior.delayMs() > 0) Thread.sleep(behavior.delayMs());
        return ResponseEntity.ok(response);
    }

    @PutMapping("/refund-behavior")
    public void behavior(
            @PathVariable String providerPath, @Valid @RequestBody MockBehavior behavior) {
        controls.configureRefund(provider(providerPath), behavior);
    }

    @PutMapping("/refund-status-behavior")
    public void statusBehavior(
            @PathVariable String providerPath, @Valid @RequestBody MockStatusBehavior behavior) {
        controls.configureRefundStatus(provider(providerPath), behavior);
    }
}
