package com.joaopaulo.activation.application;

public class DuplicateActivationRequestException extends RuntimeException {

    private final String externalId;

    public DuplicateActivationRequestException(String externalId) {
        super("Já existe uma solicitação com externalId %s e conteúdo diferente".formatted(externalId));
        this.externalId = externalId;
    }

    public String getExternalId() {
        return externalId;
    }
}
