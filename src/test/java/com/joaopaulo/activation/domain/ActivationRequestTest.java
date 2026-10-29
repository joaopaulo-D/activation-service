package com.joaopaulo.activation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ActivationRequestTest {

    private static final Instant T0 = Instant.parse("2026-10-26T14:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "client-a", "corr-1", T0);
    }

    @Test
    void startsPendingAndDueImmediately() {
        ActivationRequest request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(T0);
        assertThat(request.getCreatedAt()).isEqualTo(T0);
        assertThat(request.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void startProcessingCountsAnAttempt() {
        ActivationRequest request = newRequest();

        request.startProcessing(T1);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getProcessingStartedAt()).isEqualTo(T1);
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void completesFromProcessing() {
        ActivationRequest request = newRequest();
        request.startProcessing(T1);

        request.complete(T1);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getProcessingStartedAt()).isNull();
    }

    @Test
    void transientFailureReturnsToPendingWithNextAttempt() {
        ActivationRequest request = newRequest();
        request.startProcessing(T1);
        Instant retryAt = T1.plusSeconds(30);

        request.scheduleRetry(retryAt, "HTTP 500", T1);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(request.getLastError()).isEqualTo("HTTP 500");
        assertThat(request.getAttempts()).isEqualTo(1);
    }

    @Test
    void failureReasonIsTruncated() {
        ActivationRequest request = newRequest();
        request.startProcessing(T1);

        request.fail("x".repeat(1000), T1);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getLastError()).hasSize(ActivationRequest.MAX_ERROR_LENGTH);
    }

    @Test
    void cannotCompleteWithoutProcessing() {
        ActivationRequest request = newRequest();

        assertThatThrownBy(() -> request.complete(T1))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED");
    }

    @Test
    void completedIsFinal() {
        ActivationRequest request = newRequest();
        request.startProcessing(T1);
        request.complete(T1);

        assertThatThrownBy(() -> request.startProcessing(T1)).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.fail("x", T1)).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.scheduleRetry(T1, "x", T1)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void failedIsFinal() {
        ActivationRequest request = newRequest();
        request.startProcessing(T1);
        request.fail("HTTP 400", T1);

        assertThatThrownBy(() -> request.startProcessing(T1)).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.complete(T1)).isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void comparesContentForReplays() {
        ActivationRequest request = newRequest();

        assertThat(request.hasSameContent("CUST-1", "INTERNET-500", "client-a")).isTrue();
        assertThat(request.hasSameContent("CUST-1", "INTERNET-1000", "client-a")).isFalse();
        assertThat(request.hasSameContent("CUST-2", "INTERNET-500", "client-a")).isFalse();
        assertThat(request.hasSameContent("CUST-1", "INTERNET-500", "client-b")).isFalse();
    }
}
