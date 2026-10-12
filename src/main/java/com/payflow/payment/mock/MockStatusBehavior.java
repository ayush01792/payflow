package com.payflow.payment.mock;

import jakarta.validation.constraints.*;

public record MockStatusBehavior(@NotNull Mode mode, @Min(0) @Max(5000) int delayMs) {
    public enum Mode {
        DEFAULT,
        PROCESSING,
        UNKNOWN,
        HTTP_503,
        TIMEOUT
    }

    public static MockStatusBehavior normal() {
        return new MockStatusBehavior(Mode.DEFAULT, 0);
    }
}
