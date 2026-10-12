package com.payflow.payment.mock;

import com.payflow.payment.provider.PaymentProviderType;
import com.payflow.payment.provider.ProviderPaymentRequest;
import com.payflow.payment.provider.ProviderPaymentResult;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mock/providers/provider-a")
@ConditionalOnProperty(
        name = "payflow.mock-providers.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MockProviderAController {
    private final MockProviderStore store;
    private final MockProviderControl control;

    public MockProviderAController(MockProviderStore store, MockProviderControl control) {
        this.store = store;
        this.control = control;
    }

    @PostMapping("/payments")
    public ResponseEntity<ProviderPaymentResult> process(
            @Valid @RequestBody ProviderPaymentRequest request,
            @RequestHeader("Idempotency-Key") String key)
            throws InterruptedException {
        MockPreparedResponse response =
                store.prepare(
                        PaymentProviderType.PROVIDER_A,
                        request,
                        key,
                        control.behavior(PaymentProviderType.PROVIDER_A));
        // Latency is deliberately outside the mock's short PostgreSQL transaction.
        if (response.delayMs() > 0) Thread.sleep(response.delayMs());
        return ResponseEntity.status(response.status())
                .header(
                        "X-Mock-Not-Processed",
                        Boolean.toString(response.status() != 200 && response.status() != 409))
                .body(response.result());
    }

    @GetMapping("/payments/{reference}")
    public ResponseEntity<com.payflow.payment.provider.ProviderStatusResult> status(
            @PathVariable String reference) throws InterruptedException {
        MockStatusBehavior behavior = control.statusBehavior(PaymentProviderType.PROVIDER_A);
        if (behavior.mode() == MockStatusBehavior.Mode.HTTP_503)
            return ResponseEntity.status(503).build();
        var result = store.status(PaymentProviderType.PROVIDER_A, reference, behavior);
        if (behavior.delayMs() > 0) Thread.sleep(behavior.delayMs());
        return ResponseEntity.ok(result);
    }

    @PutMapping("/status-behavior")
    public void statusBehavior(@Valid @RequestBody MockStatusBehavior behavior) {
        control.configureStatus(PaymentProviderType.PROVIDER_A, behavior);
    }

    @PutMapping("/behavior")
    public void behavior(@Valid @RequestBody MockBehavior behavior) {
        control.configure(PaymentProviderType.PROVIDER_A, behavior);
    }
}
