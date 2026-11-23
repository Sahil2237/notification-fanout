package com.example.notifications;

import java.util.function.DoubleSupplier;

public record RetryPolicy(int maxAttempts, long baseMillis, long capMillis, DoubleSupplier random) {
    public boolean retryable(Integer status) {
        return status == null || status == 408 || status == 429 || status >= 500;
    }

    public long delayMillis(int attempt) {
        var ceiling =
                Math.min(capMillis, baseMillis * (1L << Math.min(20, Math.max(0, attempt - 1))));
        return Math.max(1, (long) (ceiling * (0.5 + random.getAsDouble() * 0.5)));
    }
}
