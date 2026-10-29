package com.joaopaulo.activation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;

// @DirtiesContext encerra o agendador ao fim da classe, para ele não consumir solicitações de outros testes
@DirtiesContext
@TestPropertySource(properties = {
        "activation.processor.enabled=true",
        "activation.processor.poll-interval=PT0.1S"
})
class AsyncActivationFlowIT extends IntegrationTest {

    @Test
    void requestIsActivatedInBackground() throws Exception {
        PROVIDER.stubFor(WireMock.post("/provider/v1/activate").willReturn(aResponse().withStatus(200)));

        String body = mvc.perform(post("/api/v1/requests").with(client("client-a"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-ASYNC","customerId":"CUST-1","productCode":"INTERNET-500"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/requests/{id}", id).with(client("client-a")))
                        .andExpect(jsonPath("$.status").value("COMPLETED")));
    }
}
