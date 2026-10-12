package com.payflow.payment.dto;

import java.util.UUID;

public record CompensationResponse(
        UUID compensationId, UUID paymentId, UUID refundId, String reason, String status) {}
