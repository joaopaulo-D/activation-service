package com.joaopaulo.activation.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    private static final RandomGenerator NO_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return 0;
        }
    };

    private static final RandomGenerator FULL_JITTER = new RandomGenerator() {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    };

    @Test
    void allowsRetryUntilMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(1), NO_JITTER);

        assertThat(policy.canRetry(1)).isTrue();
        assertThat(policy.canRetry(2)).isTrue();
        assertThat(policy.canRetry(3)).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyWithinJitterRange() {
        RetryPolicy min = new RetryPolicy(10, Duration.ofSeconds(2), Duration.ofMinutes(5), NO_JITTER);
        RetryPolicy max = new RetryPolicy(10, Duration.ofSeconds(2), Duration.ofMinutes(5), FULL_JITTER);

        assertThat(min.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(max.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(min.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(max.backoff(3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void backoffIsCapped() {
        RetryPolicy policy = new RetryPolicy(100, Duration.ofSeconds(2), Duration.ofMinutes(5), FULL_JITTER);

        assertThat(policy.backoff(50)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void respectsRetryAfterWhenLongerThanBackoff() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofMinutes(5), FULL_JITTER);

        assertThat(policy.nextAttemptAt(1, Duration.ofSeconds(120), NOW)).isEqualTo(NOW.plusSeconds(120));
        assertThat(policy.nextAttemptAt(1, Duration.ofMillis(100), NOW)).isEqualTo(NOW.plusSeconds(2));
        assertThat(policy.nextAttemptAt(1, null, NOW)).isEqualTo(NOW.plusSeconds(2));
    }
}
