package com.reputul.backend.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ConvertKitService {

    @Value("${convertkit.api.key}")
    private String apiKey;

    @Value("${convertkit.api.secret}")
    private String apiSecret;

    @Value("${convertkit.form.id.waitlist}")
    private String formId;

    @Value("${convertkit.tag.name:waitlist-2025}")
    private String tagName;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String BASE_URL = "https://api.convertkit.com/v3";

    public ConvertKitService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Add subscriber to ConvertKit form
     */
    public ConvertKitResponse addSubscriber(String email) {
        try {
            String url = String.format("%s/forms/%s/subscribe", BASE_URL, formId);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("api_secret", apiSecret); // Changed from api_key to api_secret
            requestBody.put("email", email);
            requestBody.put("tags", new String[]{tagName});

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("Adding subscriber to ConvertKit: {}", email);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());

                // Log the full response for debugging
                log.info("ConvertKit response for {}: {}", email, responseJson.toString());

                if (responseJson.has("subscription")) {
                    JsonNode subscription = responseJson.get("subscription");
                    String state = subscription.get("state").asText();

                    // Check subscriber state for bounced emails
                    String subscriberState = null;
                    if (responseJson.has("subscription") && subscription.has("subscriber")) {
                        JsonNode subscriber = subscription.get("subscriber");
                        if (subscriber.has("state")) {
                            subscriberState = subscriber.get("state").asText();
                        }
                    }

                    log.info("Subscription state: {}, Subscriber state: {}", state, subscriberState);

                    // Handle bounced emails first (invalid email addresses)
                    if ("bounced".equals(subscriberState)) {
                        log.warn("Email bounced for: {}", email);
                        return ConvertKitResponse.error("The email address you entered appears to be invalid. Please double-check and try again.");
                    }

                    // Check if this is a new subscription (created within last few minutes)
                    boolean isNewSubscription = false;
                    if (subscription.has("created_at")) {
                        String createdAt = subscription.get("created_at").asText();
                        log.info("Subscription created_at: {}", createdAt);

                        // If created_at is very recent (within last 2 minutes), treat as new
                        try {
                            java.time.Instant createdTime = java.time.Instant.parse(createdAt);
                            java.time.Instant now = java.time.Instant.now();
                            long secondsAgo = java.time.Duration.between(createdTime, now).getSeconds();
                            log.info("Subscription was created {} seconds ago", secondsAgo);
                            isNewSubscription = secondsAgo < 120; // Created within last 2 minutes
                        } catch (Exception parseException) {
                            log.warn("Could not parse created_at timestamp: {}", createdAt, parseException);
                            // If we can't parse the timestamp, assume it's new to be safe
                            isNewSubscription = true;
                        }
                    }

                    log.info("Is new subscription: {}", isNewSubscription);

                    if ("active".equals(state)) {
                        log.info("Successfully added active subscriber: {}", email);
                        return ConvertKitResponse.success("Successfully joined waitlist!");
                    } else if ("inactive".equals(state)) {
                        if (isNewSubscription) {
                            // New inactive subscription - probably needs email confirmation
                            log.info("Successfully added subscriber (pending confirmation): {}", email);
                            return ConvertKitResponse.success("Successfully joined waitlist! Please check your email to confirm your spot.");
                        } else {
                            // Old inactive subscription - truly a duplicate
                            log.info("Subscriber already exists (inactive): {}", email);
                            return ConvertKitResponse.duplicate("You're already on our waitlist!");
                        }
                    } else if ("cancelled".equals(state)) {
                        if (isNewSubscription) {
                            // Newly cancelled - probably just re-subscribed
                            log.info("Successfully re-subscribed cancelled subscriber: {}", email);
                            return ConvertKitResponse.success("Successfully joined waitlist! Please check your email to confirm your spot.");
                        } else {
                            // Old cancelled subscription - treat as duplicate
                            log.info("Subscriber already exists (cancelled): {}", email);
                            return ConvertKitResponse.duplicate("You're already on our waitlist!");
                        }
                    } else {
                        // Unknown state - log it and treat based on timing
                        log.warn("Unknown subscription state: {} for email: {}", state, email);
                        if (isNewSubscription) {
                            return ConvertKitResponse.success("Successfully joined waitlist! Please check your email to confirm your spot.");
                        } else {
                            return ConvertKitResponse.duplicate("You're already on our waitlist!");
                        }
                    }
                }

                log.info("Subscriber added successfully: {}", email);
                return ConvertKitResponse.success("Successfully joined waitlist!");
            }

            log.warn("Unexpected response from ConvertKit: {}", response.getStatusCode());
            return ConvertKitResponse.error("Something went wrong. Please try again.");

        } catch (HttpClientErrorException e) {
            log.error("ConvertKit API error for email {}: {}", email, e.getMessage());
            log.error("Response body: {}", e.getResponseBodyAsString());

            if (e.getStatusCode() == HttpStatus.BAD_REQUEST) {
                try {
                    JsonNode errorResponse = objectMapper.readTree(e.getResponseBodyAsString());
                    if (errorResponse.has("message")) {
                        String message = errorResponse.get("message").asText();
                        if (message.contains("already subscribed") || message.contains("duplicate")) {
                            return ConvertKitResponse.duplicate("You're already on our waitlist!");
                        }
                    }
                } catch (Exception parseException) {
                    log.error("Error parsing ConvertKit error response", parseException);
                }
            }

            return ConvertKitResponse.error("Unable to join waitlist. Please try again.");

        } catch (Exception e) {
            log.error("Unexpected error adding subscriber to ConvertKit", e);
            return ConvertKitResponse.error("Something went wrong. Please try again.");
        }
    }

    /**
     * Get subscriber count (optional - for future use)
     */
    public int getSubscriberCount() {
        try {
            String url = String.format("%s/forms/%s/subscriptions?api_secret=%s", BASE_URL, formId, apiSecret);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                if (responseJson.has("total_subscriptions")) {
                    return responseJson.get("total_subscriptions").asInt();
                }
            }

        } catch (Exception e) {
            log.error("Error getting subscriber count from ConvertKit", e);
        }

        return 0; // Return 0 if we can't get the count
    }

    /**
     * Response wrapper for ConvertKit operations
     */
    public static class ConvertKitResponse {
        private final boolean success;
        private final String message;
        private final boolean duplicate;

        private ConvertKitResponse(boolean success, String message, boolean duplicate) {
            this.success = success;
            this.message = message;
            this.duplicate = duplicate;
        }

        public static ConvertKitResponse success(String message) {
            return new ConvertKitResponse(true, message, false);
        }

        public static ConvertKitResponse duplicate(String message) {
            return new ConvertKitResponse(false, message, true);
        }

        public static ConvertKitResponse error(String message) {
            return new ConvertKitResponse(false, message, false);
        }

        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public boolean isDuplicate() { return duplicate; }
    }
}