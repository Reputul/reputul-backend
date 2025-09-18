package com.reputul.backend.config;

import com.reputul.backend.auth.JwtUtil;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import org.mockito.Mockito;

@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public JwtUtil jwtUtil() {
        // Mock the JwtUtil instead of trying to instantiate it
        return Mockito.mock(JwtUtil.class);
    }

    @Bean
    @Primary
    public WebClient webhookWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    @Primary
    public WebClientConfig.WebhookProperties webhookProperties() {
        return new WebClientConfig.WebhookProperties(5000, 10000, 10000, 1024 * 1024, 3, 1000);
    }
}