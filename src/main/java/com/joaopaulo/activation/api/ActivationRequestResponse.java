package com.joaopaulo.activation.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.domain.RequestStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActivationRequestResponse(
        UUID id,
        String externalId,
        String customerId,
        String productCode,
        RequestStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    static ActivationRequestResponse from(ActivationRequest request) {
        return new ActivationRequestResponse(request.getId(), request.getExternalId(), request.getCustomerId(),
                request.getProductCode(), request.getStatus(), request.getLastError(),
                request.getCreatedAt(), request.getUpdatedAt());
    }
}
