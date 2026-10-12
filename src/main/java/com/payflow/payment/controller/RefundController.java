package com.payflow.payment.controller;

import com.payflow.payment.dto.*;
import com.payflow.payment.service.RefundService;

import jakarta.validation.Valid;
import com.payflow.payment.security.MerchantPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class RefundController {
    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping("/api/v1/payments/{paymentId}/refunds")
    public ResponseEntity<RefundResponse> create(
            @AuthenticationPrincipal MerchantPrincipal principal,
            @PathVariable UUID paymentId,
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(refunds.createOwned(paymentId, principal.externalMerchantId(), request, key));
    }

    @GetMapping("/api/v1/refunds/{refundId}")
    public RefundResponse get(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable UUID refundId) {
        return refunds.getOwned(refundId, principal.externalMerchantId());
    }
}
