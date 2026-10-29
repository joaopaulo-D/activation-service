package com.joaopaulo.activation.application;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("activation.processor")
public record ProcessorProperties(
        boolean enabled,
        @Min(1) int batchSize,
        @NotNull Duration processingLease,
        @Min(1) int maxAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff) {
}
