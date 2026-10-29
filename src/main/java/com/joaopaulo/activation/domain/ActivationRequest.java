package com.joaopaulo.activation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    static final int MAX_ERROR_LENGTH = 255;

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "last_error")
    private String lastError;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivationRequest() {
        // JPA
    }

    private ActivationRequest(String externalId, String customerId, String productCode,
                              String clientId, String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.externalId = Objects.requireNonNull(externalId);
        this.customerId = Objects.requireNonNull(customerId);
        this.productCode = Objects.requireNonNull(productCode);
        this.clientId = Objects.requireNonNull(clientId);
        this.correlationId = correlationId;
        this.status = RequestStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        return new ActivationRequest(externalId, customerId, productCode, clientId, correlationId, now);
    }

    public void startProcessing(Instant now) {
        transitionTo(RequestStatus.PROCESSING, now);
        this.attempts++;
        this.processingStartedAt = now;
        this.nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(RequestStatus.COMPLETED, now);
        this.processingStartedAt = null;
        this.lastError = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(RequestStatus.FAILED, now);
        this.processingStartedAt = null;
        this.lastError = truncate(reason);
    }

    public void scheduleRetry(Instant nextAttemptAt, String reason, Instant now) {
        transitionTo(RequestStatus.PENDING, now);
        this.processingStartedAt = null;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        this.lastError = truncate(reason);
    }

    public boolean hasSameContent(String customerId, String productCode, String clientId) {
        return this.customerId.equals(customerId)
                && this.productCode.equals(productCode)
                && this.clientId.equals(clientId);
    }

    private void transitionTo(RequestStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(id, status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_ERROR_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
