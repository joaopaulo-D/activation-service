package com.joaopaulo.activation.provider;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.joaopaulo.activation.domain.ActivationRequest;

public class ProviderClient {

    static final String ACTIVATE_PATH = "/provider/v1/activate";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final RestClient restClient;
    private final Clock clock;

    public ProviderClient(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    public ProviderResult activate(ActivationRequest request) {
        long start = System.nanoTime();
        try {
            ProviderResult result = restClient.post()
                    .uri(ACTIVATE_PATH)
                    // mesma chave em todas as tentativas: o provedor deduplica o reenvio após um timeout
                    .header(IDEMPOTENCY_KEY_HEADER, request.getId().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ActivationPayload(request.getId(), request.getExternalId(),
                            request.getCustomerId(), request.getProductCode()))
                    .exchange((httpRequest, response) -> classify(response.getStatusCode(), response.getHeaders()),
                            true);
            log.info("Provedor respondeu para a solicitação {} em {} ms: {}",
                    request.getId(), elapsedMillis(start), result);
            return result;
        } catch (ResourceAccessException e) {
            boolean timeout = e.getCause() instanceof HttpTimeoutException;
            String reason = timeout ? "timeout ao chamar o provedor" : "falha de comunicação com o provedor";
            log.warn("{} para a solicitação {} após {} ms: {}",
                    reason, request.getId(), elapsedMillis(start), e.getMessage());
            return new ProviderResult.TransientFailure(reason);
        }
    }

    ProviderResult classify(HttpStatusCode status, HttpHeaders headers) {
        if (status.is2xxSuccessful()) {
            return new ProviderResult.Success();
        }
        int code = status.value();
        String reason = "provedor respondeu HTTP " + code;
        if (code == HttpStatus.TOO_MANY_REQUESTS.value() || code == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            return new ProviderResult.TransientFailure(reason, parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER)));
        }
        if (status.is5xxServerError() || code == HttpStatus.REQUEST_TIMEOUT.value()) {
            return new ProviderResult.TransientFailure(reason);
        }
        return new ProviderResult.PermanentFailure(reason);
    }

    Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            // tenta o formato de data
        }
        try {
            Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(clock.instant(), at);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    record ActivationPayload(UUID requestId, String externalId, String customerId, String productCode) {
    }
}
