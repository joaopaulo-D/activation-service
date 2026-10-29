package com.joaopaulo.activation.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.observability.CorrelationId;

class ProviderClientTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:00:00Z");

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final ActivationRequest request =
            ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "client-a", null, NOW);

    private ProviderClient client() {
        ProviderProperties properties = new ProviderProperties(URI.create(provider.baseUrl()),
                Duration.ofSeconds(1), Duration.ofMillis(300), "secret-key");
        RestClient restClient = ProviderClientConfig.providerRestClient(RestClient.builder(), properties);
        return new ProviderClient(restClient, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successSendsIdempotencyKeyCorrelationAndCredential() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(200)));
        MDC.put(CorrelationId.MDC_KEY, "corr-123456");

        ProviderResult result = client().activate(request);

        assertThat(result).isInstanceOf(ProviderResult.Success.class);
        provider.verify(postRequestedFor(urlEqualTo(ProviderClient.ACTIVATE_PATH))
                .withHeader(ProviderClient.IDEMPOTENCY_KEY_HEADER, equalTo(request.getId().toString()))
                .withHeader(CorrelationId.HEADER, equalTo("corr-123456"))
                .withHeader(ProviderClientConfig.API_KEY_HEADER, equalTo("secret-key"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","externalId":"EXT-1","customerId":"CUST-1","productCode":"INTERNET-500"}
                        """.formatted(request.getId()))));
    }

    @Test
    void badRequestIsPermanent() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(400)));

        assertThat(client().activate(request))
                .isEqualTo(new ProviderResult.PermanentFailure("provedor respondeu HTTP 400"));
    }

    @Test
    void serverErrorIsTransient() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH).willReturn(aResponse().withStatus(500)));

        assertThat(client().activate(request))
                .isEqualTo(new ProviderResult.TransientFailure("provedor respondeu HTTP 500"));
    }

    @Test
    void tooManyRequestsCarriesRetryAfterSeconds() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));

        assertThat(client().activate(request))
                .isEqualTo(new ProviderResult.TransientFailure("provedor respondeu HTTP 429", Duration.ofSeconds(120)));
    }

    @Test
    void serviceUnavailableCarriesRetryAfterDate() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "Mon, 26 Oct 2026 14:01:00 GMT")));

        assertThat(client().activate(request))
                .isEqualTo(new ProviderResult.TransientFailure("provedor respondeu HTTP 503", Duration.ofSeconds(60)));
    }

    @Test
    void timeoutIsTransient() {
        provider.stubFor(post(ProviderClient.ACTIVATE_PATH)
                .willReturn(aResponse().withStatus(200).withFixedDelay(1_000)));

        assertThat(client().activate(request))
                .isEqualTo(new ProviderResult.TransientFailure("timeout ao chamar o provedor"));
    }

    @Test
    void connectionFailureIsTransient() {
        ProviderProperties properties = new ProviderProperties(URI.create("http://localhost:1"),
                Duration.ofMillis(300), Duration.ofMillis(300), null);
        ProviderClient unreachable = new ProviderClient(
                ProviderClientConfig.providerRestClient(RestClient.builder(), properties), Clock.systemUTC());

        assertThat(unreachable.activate(request))
                .isEqualTo(new ProviderResult.TransientFailure("falha de comunicação com o provedor"));
    }
}
