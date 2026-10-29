package com.joaopaulo.activation.application;

import java.util.Random;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProcessorConfig {

    @Bean
    RetryPolicy retryPolicy(ProcessorProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.initialBackoff(), properties.maxBackoff(),
                new Random());
    }
}
