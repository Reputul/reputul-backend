package com.reputul.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Service for generating AI-powered review replies using Anthropic Claude
 * Uses Claude 3.5 Sonnet for natural, empathetic customer service responses
 */
@Service
@Slf4j
public class ClaudeService {

    @Value("${anthropic.api.key}")
    private String anthropicApiKey;

    @Value("${anthropic.api.url:https://api.anthropic.com/v1/messages}")
    private String anthropicApiUrl;

    @Value("${anthropic.model:claude-3-5-sonnet-20241022}")
    private String model;

    @Value("${anthropic.version:2023-06-01}")
    private String apiVersion;

    private final RestTemplate restTemplate;

    public ClaudeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Generate a professional reply to a review using Claude
     *
     * @param reviewText   The text of the review
     * @param rating       Star rating (1-5)
     * @param reviewerName Name of the reviewer
     * @param businessName Name of the business
     * @return Generated reply text
     */
    public String generateReviewReply(
            String reviewText,
            Integer rating,
            String reviewerName,
            String businessName
    ) {
        // Check if API key is configured
        if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
            log.warn("Anthropic API key not configured, using fallback reply");
            return generateFallbackReply(rating, reviewerName, businessName);
        }

        try {
            // Determine tone based on rating
            String tone = rating >= 4 ? "grateful and professional" : "apologetic and understanding";

            // Build the prompt
            String systemPrompt = buildSystemPrompt(businessName, tone);
            String userPrompt = buildUserPrompt(reviewText, rating, reviewerName);

            // Create request body (Claude API format)
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "max_tokens", 300,
                    "system", systemPrompt,
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", userPrompt
                            )
                    )
            );

            // Set headers (Claude-specific)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", anthropicApiKey);
            headers.set("anthropic-version", apiVersion);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            // Call Anthropic API
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    anthropicApiUrl,
                    request,
                    Map.class
            );

            // Extract reply from response
            if (response.getBody() != null && response.getBody().containsKey("content")) {
                List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
                if (!content.isEmpty()) {
                    Map<String, Object> textBlock = content.get(0);
                    if ("text".equals(textBlock.get("type"))) {
                        String reply = (String) textBlock.get("text");

                        log.info("Successfully generated Claude AI reply for review (rating: {})", rating);
                        return reply.trim();
                    }
                }
            }

            log.warn("No content in Claude API response");
            return generateFallbackReply(rating, reviewerName, businessName);

        } catch (Exception e) {
            log.error("Error calling Claude API: {}", e.getMessage(), e);
            return generateFallbackReply(rating, reviewerName, businessName);
        }
    }

    /**
     * Build system prompt for Claude
     */
    private String buildSystemPrompt(String businessName, String tone) {
        return String.format(
                "You are a professional customer service representative for %s. " +
                        "Generate a %s reply to customer reviews. " +
                        "The reply should be:\n" +
                        "- Concise (2-3 sentences max)\n" +
                        "- Personable and authentic\n" +
                        "- Address the specific points mentioned in the review\n" +
                        "- End with a warm closing\n" +
                        "- Never use emojis\n" +
                        "- Sign off naturally without adding 'Best regards' or similar (the business will add this automatically)\n" +
                        "- Use a conversational, human tone that builds genuine connection\n" +
                        "- Be empathetic and show you truly care about the customer's experience",
                businessName,
                tone
        );
    }

    /**
     * Build user prompt with review details
     */
    private String buildUserPrompt(String reviewText, Integer rating, String reviewerName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(String.format("Review Rating: %d/5 stars\n", rating));

        if (reviewerName != null && !reviewerName.isEmpty()) {
            prompt.append(String.format("Reviewer Name: %s\n", reviewerName));
        }

        if (reviewText != null && !reviewText.isEmpty()) {
            prompt.append(String.format("Review Text: \"%s\"\n", reviewText));
        }

        prompt.append("\nGenerate a professional reply to this review.");

        return prompt.toString();
    }

    /**
     * Generate a simple fallback reply if Claude API fails
     */
    private String generateFallbackReply(Integer rating, String reviewerName, String businessName) {
        String greeting = (reviewerName != null && !reviewerName.isEmpty())
                ? "Hi " + reviewerName + ","
                : "Hello,";

        if (rating >= 4) {
            return String.format(
                    "%s Thank you so much for your wonderful review! " +
                            "We're thrilled to hear you had a great experience with %s. " +
                            "We look forward to serving you again soon!",
                    greeting,
                    businessName
            );
        } else {
            return String.format(
                    "%s Thank you for taking the time to share your feedback. " +
                            "We're sorry to hear your experience didn't meet expectations. " +
                            "Please reach out to us directly so we can make things right.",
                    greeting
            );
        }
    }
}