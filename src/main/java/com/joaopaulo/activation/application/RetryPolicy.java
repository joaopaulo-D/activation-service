package com.joaopaulo.activation.application;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

public class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final RandomGenerator random;

    public RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, RandomGenerator random) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.random = random;
    }

    public boolean canRetry(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    // equal jitter (metade fixa + metade aleatória), nunca antes do Retry-After do provedor
    public Instant nextAttemptAt(int attemptsMade, Duration retryAfter, Instant now) {
        Duration delay = backoff(attemptsMade);
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        return now.plus(delay);
    }

    Duration backoff(int attemptsMade) {
        int exponent = Math.min(Math.max(attemptsMade - 1, 0), 30);
        long exponentialMillis = initialBackoff.toMillis() * (1L << exponent);
        long cappedMillis = Math.min(exponentialMillis, maxBackoff.toMillis());
        long half = cappedMillis / 2;
        long jitter = half > 0 ? random.nextLong(half + 1) : 0;
        return Duration.ofMillis(cappedMillis - half + jitter);
    }
}
