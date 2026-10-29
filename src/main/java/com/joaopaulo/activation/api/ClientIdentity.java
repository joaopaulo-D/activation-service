package com.joaopaulo.activation.api;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

final class ClientIdentity {

    private ClientIdentity() {
    }

    static String of(Jwt jwt) {
        for (String claim : new String[] {"azp", "client_id"}) {
            String value = jwt.getClaimAsString(claim);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return jwt.getSubject();
    }
}
