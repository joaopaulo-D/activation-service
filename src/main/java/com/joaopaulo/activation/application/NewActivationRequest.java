package com.joaopaulo.activation.application;

public record NewActivationRequest(String externalId, String customerId, String productCode) {
}
