package com.payflow.payment.mock;

import com.payflow.payment.provider.ProviderPaymentResult;

public record MockPreparedResponse(int status, ProviderPaymentResult result, int delayMs) {}
