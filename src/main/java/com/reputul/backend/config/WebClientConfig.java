package com.reputul.backend.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(WebClientConfig.WebhookProperties.class) // FIXED: Add this annotation
public class WebClientConfig {

    @Bean
    public WebClient webhookWebClient(WebhookProperties webhookProperties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, webhookProperties.connectTimeout())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(webhookProperties.readTimeout(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(webhookProperties.writeTimeout(), TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(webhookProperties.maxInMemorySize()))
                .build();
    }

    @ConfigurationProperties(prefix = "automation.webhook")
    public record WebhookProperties(
            int connectTimeout,
            int readTimeout,
            int writeTimeout,
            int maxInMemorySize,
            int maxRetries,
            int retryDelayMs
    ) {
        public WebhookProperties() {
            this(5000, 10000, 10000, 1024 * 1024, 3, 1000);
        }
    }
}