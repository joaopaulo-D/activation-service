package com.joaopaulo.activation.provider;

import java.time.Duration;

public sealed interface ProviderResult {

    record Success() implements ProviderResult {
    }

    record PermanentFailure(String reason) implements ProviderResult {
    }

    record TransientFailure(String reason, Duration retryAfter) implements ProviderResult {

        public TransientFailure(String reason) {
            this(reason, null);
        }
    }
}
