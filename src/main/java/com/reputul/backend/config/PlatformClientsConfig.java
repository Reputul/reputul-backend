package com.reputul.backend.config;

import com.reputul.backend.integrations.FacebookClient;
import com.reputul.backend.integrations.GoogleMyBusinessClient;
import com.reputul.backend.integrations.PlatformReviewClient;
import com.reputul.backend.models.ChannelCredential;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for platform review clients
 * Creates a map of all available platform clients for dependency injection
 */
@Configuration
public class PlatformClientsConfig {

    /**
     * Creates a map of platform clients that can be injected into controllers and services
     *
     * @param googleClient The Google My Business client
     * @param facebookClient The Facebook client
     * @return Map of platform type to client implementation
     */
    @Bean
    public Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients(
            GoogleMyBusinessClient googleClient,
            FacebookClient facebookClient) {

        Map<ChannelCredential.PlatformType, PlatformReviewClient> clients = new HashMap<>();

        clients.put(ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS, googleClient);
        clients.put(ChannelCredential.PlatformType.FACEBOOK, facebookClient);

        // Add more platforms as you implement them:
        // clients.put(ChannelCredential.PlatformType.YELP, yelpClient);
        // clients.put(ChannelCredential.PlatformType.TRUSTPILOT, trustpilotClient);

        return clients;
    }
}