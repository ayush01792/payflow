package com.payflow.payment.mock;

public enum MockScenario {
    SUCCESS,
    DECLINE,
    HTTP_500,
    TIMEOUT,
    TIMEOUT_DECLINE,
    SLOW_SUCCESS,
    HTTP_400,
    HTTP_401,
    HTTP_403
}
