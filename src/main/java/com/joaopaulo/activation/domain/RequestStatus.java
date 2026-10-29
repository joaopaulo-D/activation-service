package com.joaopaulo.activation.domain;

public enum RequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(RequestStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
