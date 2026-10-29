package com.joaopaulo.activation.api;

import java.net.URI;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import com.joaopaulo.activation.observability.CorrelationId;

public final class ProblemDetails {

    private static final String TYPE_BASE = "https://api.activation-service.dev/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatusCode status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + type));
        problem.setTitle(title);
        return withTraceId(problem);
    }

    public static ProblemDetail withTraceId(ProblemDetail problem) {
        String correlationId = CorrelationId.current();
        if (correlationId != null) {
            problem.setProperty("traceId", correlationId);
        }
        return problem;
    }
}
