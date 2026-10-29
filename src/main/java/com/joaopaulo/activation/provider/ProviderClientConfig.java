package com.joaopaulo.activation.provider;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import com.joaopaulo.activation.observability.CorrelationId;

@Configuration(proxyBeanMethods = false)
public class ProviderClientConfig {

    static final String API_KEY_HEADER = "X-Api-Key";

    @Bean
    ProviderClient providerClient(RestClient.Builder builder, ProviderProperties properties, Clock clock) {
        return new ProviderClient(providerRestClient(builder, properties), clock);
    }

    static RestClient providerRestClient(RestClient.Builder builder, ProviderProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(properties.apiKey())) {
                        headers.set(API_KEY_HEADER, properties.apiKey());
                    }
                })
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = CorrelationId.current();
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
