// WebConfig.java - UPDATED VERSION
package com.reputul.backend.config;

import com.reputul.backend.security.CurrentUserMethodArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.*;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed.origins:https://reputul.com,https://www.reputul.com,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173}")
    private String allowedOrigins;

    private final CurrentUserMethodArgumentResolver currentUserResolver;

    public WebConfig(CurrentUserMethodArgumentResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Split the comma-separated origins from environment variable
        String[] origins = allowedOrigins.split(",");

        System.out.println("CORS allowed origins: " + java.util.Arrays.toString(origins));

        registry.addMapping("/**")  // Allow all endpoints
                .allowedOrigins(origins)  // Use allowedOrigins instead of allowedOriginPatterns
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers")
                .allowCredentials(true)
                .maxAge(3600); // Cache preflight for 1 hour
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}