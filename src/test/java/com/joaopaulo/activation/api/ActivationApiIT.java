package com.joaopaulo.activation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.joaopaulo.activation.IntegrationTest;

class ActivationApiIT extends IntegrationTest {

    private ResultActions create(String clientId, String externalId, String customerId, String productCode)
            throws Exception {
        return mvc.perform(post("/api/v1/requests").with(client(clientId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalId":"%s","customerId":"%s","productCode":"%s"}
                        """.formatted(externalId, customerId, productCode)));
    }

    private String createAndGetId(String clientId, String externalId, String customerId) throws Exception {
        String body = create(clientId, externalId, customerId, "INTERNET-500")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    @Test
    void createdRequestCanBeRetrieved() throws Exception {
        String id = createAndGetId("client-a", "EXT-1", "CUST-1");

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("client-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.externalId").value("EXT-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void retryWithSameContentReturnsTheSameRequest() throws Exception {
        String id = createAndGetId("client-a", "EXT-1", "CUST-1");

        create("client-a", "EXT-1", "CUST-1", "INTERNET-500")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentProductIsConflict() throws Exception {
        createAndGetId("client-a", "EXT-1", "CUST-1");

        create("client-a", "EXT-1", "CUST-1", "INTERNET-1000")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Solicitação duplicada"));

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void unknownRequestIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client("client-a")))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestOfAnotherClientIsNotFound() throws Exception {
        String id = createAndGetId("client-a", "EXT-1", "CUST-1");

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("client-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listFiltersByCustomerAndOnlyReturnsOwnRequests() throws Exception {
        createAndGetId("client-a", "EXT-1", "CUST-1");
        createAndGetId("client-a", "EXT-2", "CUST-1");
        createAndGetId("client-a", "EXT-3", "CUST-2");
        createAndGetId("client-b", "EXT-4", "CUST-1");

        mvc.perform(get("/api/v1/requests").param("customerId", "CUST-1").param("status", "PENDING")
                        .param("size", "1").with(client("client-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-2"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mvc.perform(get("/api/v1/requests").param("status", "COMPLETED").with(client("client-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
