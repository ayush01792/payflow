package com.payflow.payment.event;

import java.util.UUID;

/** Local, after-commit cache invalidation signal. Never serialized to Kafka. */
public record PaymentStatusChanged(UUID paymentId) {}
