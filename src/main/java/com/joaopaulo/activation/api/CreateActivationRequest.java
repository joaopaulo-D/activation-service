package com.joaopaulo.activation.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.joaopaulo.activation.application.NewActivationRequest;

public record CreateActivationRequest(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '-' ou '_'")
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '-' ou '_'")
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '-' ou '_'")
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9_-]*$";

    NewActivationRequest toCommand() {
        return new NewActivationRequest(externalId, customerId, productCode);
    }
}
