package com.payflow.payment.mock;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record MockBehavior(
        @NotNull MockScenario scenario,
        @Min(0) @Max(10) int failFirst,
        @Min(0) @Max(5000) int delayMs) {
    public static MockBehavior success() {
        return new MockBehavior(MockScenario.SUCCESS, 0, 0);
    }
}
