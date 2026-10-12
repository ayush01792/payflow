package com.payflow.payment.mock;

import com.payflow.payment.provider.ProviderRefundResult;

public record MockRefundResponse(int status, ProviderRefundResult result, int delayMs) {}
