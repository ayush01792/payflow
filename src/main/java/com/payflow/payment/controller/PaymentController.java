package com.payflow.payment.controller;

import com.payflow.payment.security.MerchantPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.net.URI;
import java.util.UUID;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.PageResponse;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.service.PaymentCreationResult;
import com.payflow.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(@AuthenticationPrincipal MerchantPrincipal principal, @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentCreationResult result = service.createPayment(principal.externalMerchantId(), request, idempotencyKey);
        URI location = URI.create("/api/v1/payments/" + result.payment().id());
        return result.created() ? ResponseEntity.created(location).body(result.payment())
                : ResponseEntity.ok().location(location).body(result.payment());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(@AuthenticationPrincipal MerchantPrincipal principal, @PathVariable UUID paymentId) {
        return service.getPayment(paymentId, principal.externalMerchantId());
    }

    @GetMapping
    public PageResponse<PaymentResponse> listPayments(@AuthenticationPrincipal MerchantPrincipal principal, @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     Sort sort) {
        return service.listPayments(principal.externalMerchantId(), page, size, sort);
    }
}
