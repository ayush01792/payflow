package com.payflow.payment.service;

import com.payflow.payment.dto.*;
import com.payflow.payment.exception.InvalidRefundRequestException;
import com.payflow.payment.exception.RefundNotFoundException;
import com.payflow.payment.refund.*;
import com.payflow.payment.repository.RefundRepository;

import jakarta.validation.Validator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RefundService {
    private final RefundCreationStore creation;
    private final RefundRepository repository;
    private final Validator validator;

    public RefundService(
            RefundCreationStore creation, RefundRepository repository, Validator validator) {
        this.creation = creation;
        this.repository = repository;
        this.validator = validator;
    }

    @Transactional
    public RefundResponse create(UUID payment, CreateRefundRequest request, String key) {
        validate(request, key);
        return creation.create(payment, request, key);
    }

    private void validate(CreateRefundRequest request, String key) {
        if (key == null
                || key.isBlank()
                || key.length() > 128
                || request == null
                || !validator.validate(request).isEmpty())
            throw new InvalidRefundRequestException(
                    "Valid refund request and Idempotency-Key (1-128 characters) required");
    }

    @Transactional
    public RefundResponse createOwned(UUID payment, String merchantId, CreateRefundRequest request, String key) {
        validate(request,key);
        return creation.createOwned(payment,merchantId,request,key);
    }

    @Transactional(readOnly = true)
    public RefundResponse getOwned(UUID id, String merchantId) {
        return response(repository.findOwned(id, merchantId).orElseThrow(() -> new RefundNotFoundException("Refund not found: " + id)));
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID id) {
        var refund =
                repository
                        .findById(id)
                        .orElseThrow(() -> new RefundNotFoundException("Refund not found: " + id));
        return response(refund);
    }

    private RefundResponse response(com.payflow.payment.entity.Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getStatus(),
                refund.getMerchantRefundReference(),
                refund.getProvider(),
                refund.getProviderRefundId(),
                refund.getFailureCode(),
                refund.getFailureMessage(),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }
}
