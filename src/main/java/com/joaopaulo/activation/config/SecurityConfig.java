package com.joaopaulo.activation.config;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.joaopaulo.activation.api.ProblemDetails;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String SCOPE_READ = "SCOPE_activation:read";
    static final String SCOPE_WRITE = "SCOPE_activation:write";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint entryPoint = problemEntryPoint(objectMapper);
        AccessDeniedHandler accessDeniedHandler = problemAccessDeniedHandler(objectMapper);

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/requests").hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/requests", "/api/v1/requests/*").hasAuthority(SCOPE_READ)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    private static AuthenticationEntryPoint problemEntryPoint(ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            delegate.commence(request, response, exception);
            writeProblem(request, response, objectMapper, ProblemDetails.of(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Não autenticado", "Token de acesso ausente, inválido ou expirado."));
        };
    }

    private static AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
        BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            delegate.handle(request, response, exception);
            writeProblem(request, response, objectMapper, ProblemDetails.of(HttpStatus.FORBIDDEN, "forbidden",
                    "Acesso negado", "O token não possui o escopo necessário para esta operação."));
        };
    }

    private static void writeProblem(HttpServletRequest request, HttpServletResponse response,
                                     ObjectMapper objectMapper, ProblemDetail problem) throws IOException {
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
