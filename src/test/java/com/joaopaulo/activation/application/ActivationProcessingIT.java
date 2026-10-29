package com.joaopaulo.activation.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import com.joaopaulo.activation.IntegrationTest;
import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.domain.RequestStatus;

class ActivationProcessingIT extends IntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";

    @Autowired
    private ActivationProcessor processor;

    @Autowired
    private ActivationRequestService service;

    private UUID newRequest() {
        return service.create(new NewActivationRequest("EXT-" + UUID.randomUUID(), "CUST-1", "INTERNET-500"),
                "client-a").request().getId();
    }

    private ActivationRequest reload(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    private static void providerResponds(ResponseDefinitionBuilder response) {
        PROVIDER.stubFor(WireMock.post(ACTIVATE).willReturn(response));
    }

    private static void providerRespondsThenSucceeds(ResponseDefinitionBuilder first) {
        PROVIDER.stubFor(WireMock.post(ACTIVATE).inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(first).willSetStateTo("recovered"));
        PROVIDER.stubFor(WireMock.post(ACTIVATE).inScenario("retry").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));
    }

    @Test
    void successCompletesAndPropagatesCorrelationId() throws Exception {
        providerResponds(aResponse().withStatus(200));
        String body = mvc.perform(post("/api/v1/requests").with(client("client-a"))
                        .header("X-Correlation-Id", "corr-from-origin-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-1","customerId":"CUST-1","productCode":"INTERNET-500"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(processor.processDueRequests()).isEqualTo(1);

        ActivationRequest request = reload(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getAttempts()).isEqualTo(1);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(id.toString()))
                .withHeader("X-Correlation-Id", equalTo("corr-from-origin-1")));
    }

    @Test
    void badRequestFailsWithoutRetry() {
        providerResponds(aResponse().withStatus(400));
        UUID id = newRequest();

        processor.processDueRequests();

        ActivationRequest request = reload(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getLastError()).isEqualTo("provedor respondeu HTTP 400");
        assertThat(processor.processDueRequests()).isZero();
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void serverErrorIsRetriedAndThenCompletes() {
        providerRespondsThenSucceeds(aResponse().withStatus(500));
        UUID id = newRequest();

        processor.processDueRequests();
        ActivationRequest afterFailure = reload(id);
        assertThat(afterFailure.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).isEqualTo("provedor respondeu HTTP 500");

        processor.processDueRequests();
        ActivationRequest afterRetry = reload(id);
        assertThat(afterRetry.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(afterRetry.getAttempts()).isEqualTo(2);
        assertThat(afterRetry.getLastError()).isNull();
    }

    @Test
    void persistentServerErrorFailsAfterMaxAttempts() {
        providerResponds(aResponse().withStatus(500));
        UUID id = newRequest();

        for (int i = 0; i < 5; i++) {
            processor.processDueRequests();
        }

        ActivationRequest request = reload(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getLastError()).startsWith("tentativas esgotadas (3)");
        PROVIDER.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void timeoutIsRetriedWithSameIdempotencyKey() {
        providerRespondsThenSucceeds(aResponse().withStatus(200).withFixedDelay(1_500));
        UUID id = newRequest();

        processor.processDueRequests();
        ActivationRequest afterTimeout = reload(id);
        assertThat(afterTimeout.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(afterTimeout.getLastError()).isEqualTo("timeout ao chamar o provedor");

        processor.processDueRequests();
        assertThat(reload(id).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        PROVIDER.verify(2, postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(id.toString())));
    }

    @Test
    void tooManyRequestsWaitsForRetryAfter() {
        providerResponds(aResponse().withStatus(429).withHeader("Retry-After", "120"));
        UUID id = newRequest();
        Instant before = Instant.now();

        processor.processDueRequests();

        ActivationRequest request = reload(id);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(119));
        assertThat(processor.processDueRequests()).as("ainda não é hora de tentar de novo").isZero();
    }

    @Test
    void stuckProcessingIsReleasedAndSentAgain() {
        providerResponds(aResponse().withStatus(200));
        UUID id = newRequest();
        ActivationRequest request = reload(id);
        request.startProcessing(Instant.now().minus(5, ChronoUnit.MINUTES));
        repository.saveAndFlush(request);

        assertThat(processor.processDueRequests()).as("em PROCESSING não é elegível").isZero();
        assertThat(processor.releaseStuckRequests()).isEqualTo(1);
        assertThat(reload(id).getStatus()).isEqualTo(RequestStatus.PENDING);

        processor.processDueRequests();
        assertThat(reload(id).getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(reload(id).getAttempts()).isEqualTo(2);
    }
}
