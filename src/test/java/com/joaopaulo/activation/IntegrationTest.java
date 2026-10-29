package com.joaopaulo.activation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.joaopaulo.activation.domain.ActivationRequestRepository;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "activation.processor.enabled=false",
        "activation.processor.max-attempts=3",
        "activation.processor.initial-backoff=PT0S",
        "activation.processor.max-backoff=PT0S",
        "activation.processor.processing-lease=PT1M",
        "activation.provider.read-timeout=PT0.5S"
})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    protected static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("activation.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ActivationRequestRepository repository;

    @BeforeEach
    void resetState() {
        repository.deleteAllInBatch();
        PROVIDER.resetAll();
    }

    protected static RequestPostProcessor client(String clientId) {
        return jwt().jwt(token -> token.claim("azp", clientId))
                .authorities(new SimpleGrantedAuthority("SCOPE_activation:read"),
                        new SimpleGrantedAuthority("SCOPE_activation:write"));
    }
}
