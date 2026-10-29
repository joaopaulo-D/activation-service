package com.joaopaulo.activation.config;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApplicationConfig {

    // precisão de milissegundos: o valor devolvido no POST bate com o que o banco retorna depois
    @Bean
    Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }
}
