package com.joaopaulo.activation.application;

import com.joaopaulo.activation.domain.ActivationRequest;

public record CreationResult(ActivationRequest request, boolean created) {
}
