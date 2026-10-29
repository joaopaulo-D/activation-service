package com.joaopaulo.activation.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static String sanitizeOrGenerate(String candidate) {
        if (candidate != null && VALID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
