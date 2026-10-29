package com.joaopaulo.activation.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.joaopaulo.activation.application.ActivationRequestNotFoundException;
import com.joaopaulo.activation.application.ActivationRequestService;
import com.joaopaulo.activation.application.CreationResult;
import com.joaopaulo.activation.application.DuplicateActivationRequestException;
import com.joaopaulo.activation.application.NewActivationRequest;
import com.joaopaulo.activation.config.SecurityConfig;
import com.joaopaulo.activation.domain.ActivationRequest;
import com.joaopaulo.activation.observability.CorrelationId;

@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    private static RequestPostProcessor client(String... scopes) {
        return jwt().jwt(token -> token.claim("azp", "client-a"))
                .authorities(Arrays.stream(scopes).<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList());
    }

    private static MockHttpServletRequestBuilder postJson(String body) {
        return post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ActivationRequest sample() {
        return ActivationRequest.create("EXT-12345", "CUST-10001", "INTERNET-500", "client-a", null,
                Instant.parse("2026-10-26T14:03:11Z"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        ActivationRequest request = sample();
        when(service.create(eq(new NewActivationRequest("EXT-12345", "CUST-10001", "INTERNET-500")), eq("client-a")))
                .thenReturn(new CreationResult(request, true));

        mvc.perform(postJson(VALID_BODY).with(client("activation:write")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/requests/" + request.getId())))
                .andExpect(jsonPath("$.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void replayReturns200WithExistingRequest() throws Exception {
        ActivationRequest request = sample();
        when(service.create(any(), any())).thenReturn(new CreationResult(request, false));

        mvc.perform(postJson(VALID_BODY).with(client("activation:write")))
                .andExpect(status().isOk())
                .andExpect(header().string(ActivationRequestController.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(request.getId().toString()));
    }

    @Test
    void invalidBodyReturnsProblemWithFieldErrors() throws Exception {
        mvc.perform(postJson("""
                        {"externalId":"EXT 1","productCode":"%s"}
                        """.formatted("P".repeat(65))).with(client("activation:write"))
                        .header(CorrelationId.HEADER, "trace-abc-123"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(endsWith("/validation-error")))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                .andExpect(jsonPath("$.traceId").value("trace-abc-123"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"));
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(postJson("{not json").with(client("activation:write")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(endsWith("/malformed-request")))
                .andExpect(jsonPath("$.detail").value(not(containsString("Exception"))));
    }

    @Test
    void conflictingDuplicateReturns409() throws Exception {
        when(service.create(any(), any())).thenThrow(new DuplicateActivationRequestException("EXT-12345"));

        mvc.perform(postJson(VALID_BODY).with(client("activation:write")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("/duplicate-request")))
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void getReturnsRequest() throws Exception {
        ActivationRequest request = sample();
        when(service.get(request.getId(), "client-a")).thenReturn(request);

        mvc.perform(get("/api/v1/requests/{id}", request.getId()).with(client("activation:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void getUnknownReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id, "client-a")).thenThrow(new ActivationRequestNotFoundException(id));

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("activation:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("/not-found")));
    }

    @Test
    void invalidIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/requests/not-a-uuid").with(client("activation:read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void pageSizeAboveLimitReturns400() throws Exception {
        mvc.perform(get("/api/v1/requests").param("size", "500").with(client("activation:read")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        when(service.create(any(), any())).thenThrow(new IllegalStateException("connection pool secret details"));

        mvc.perform(postJson(VALID_BODY).with(client("activation:write")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(endsWith("/internal-error")))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mvc.perform(postJson(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void readScopeCannotCreate() throws Exception {
        mvc.perform(postJson(VALID_BODY).with(client("activation:read")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(endsWith("/forbidden")));
    }

    @Test
    void writeScopeCannotRead() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(client("activation:write")))
                .andExpect(status().isForbidden());
    }
}
