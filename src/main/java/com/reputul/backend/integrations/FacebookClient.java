package com.reputul.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.exceptions.TokenExpiredException;
import com.reputul.backend.models.ChannelCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Facebook Page Reviews integration
 *
 * UPDATED: Uses page access token from metadata for API calls.
 * Removed legacy scraping logic in favor of "Business Asset User Profile Access" feature.
 */
@Service
@Slf4j
public class FacebookClient implements PlatformReviewClient {

    @Value("${facebook.app.id}")
    private String appId;

    @Value("${facebook.app.secret}")
    private String appSecret;

    @Value("${facebook.oauth.redirect-uri}")
    private String redirectUri;

    private static final String OAUTH_URL = "https://www.facebook.com/v18.0/dialog/oauth";
    private static final String TOKEN_URL = "https://graph.facebook.com/v18.0/oauth/access_token";
    private static final String TOKEN_EXCHANGE_URL = "https://graph.facebook.com/v18.0/oauth/access_token";
    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v18.0";

    // Facebook permissions for pages and ratings
    private static final String PERMISSIONS = "pages_show_list,pages_read_engagement,pages_manage_metadata";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FacebookClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChannelCredential.PlatformType getPlatformType() {
        return ChannelCredential.PlatformType.FACEBOOK;
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUriParam) {
        String finalRedirectUri = redirectUriParam != null ? redirectUriParam : this.redirectUri;

        return UriComponentsBuilder.fromHttpUrl(OAUTH_URL)
                .queryParam("client_id", appId)
                .queryParam("redirect_uri", finalRedirectUri)
                .queryParam("scope", PERMISSIONS)
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    @Override
    public OAuthTokenResponse exchangeCodeForToken(String code, String redirectUri)
            throws PlatformIntegrationException {
        try {
            // Step 1: Exchange code for short-lived token
            String url = UriComponentsBuilder.fromHttpUrl(TOKEN_URL)
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("redirect_uri", redirectUri)
                    .queryParam("code", code)
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to exchange code for token");
            }

            JsonNode tokenData = objectMapper.readTree(response.getBody());
            String shortLivedToken = tokenData.get("access_token").asText();

            // Step 2: Exchange short-lived token for long-lived token (60 days)
            String longLivedUrl = UriComponentsBuilder.fromHttpUrl(TOKEN_EXCHANGE_URL)
                    .queryParam("grant_type", "fb_exchange_token")
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("fb_exchange_token", shortLivedToken)
                    .build()
                    .toUriString();

            ResponseEntity<String> longLivedResponse = restTemplate.getForEntity(longLivedUrl, String.class);

            if (!longLivedResponse.getStatusCode().is2xxSuccessful() || longLivedResponse.getBody() == null) {
                throw new PlatformIntegrationException("Failed to get long-lived token");
            }

            JsonNode longLivedData = objectMapper.readTree(longLivedResponse.getBody());
            String accessToken = longLivedData.get("access_token").asText();
            int expiresIn = longLivedData.has("expires_in") ?
                    longLivedData.get("expires_in").asInt() : 5184000; // Default 60 days

            log.info("Successfully obtained Facebook long-lived token (expires in {} seconds)", expiresIn);

            return OAuthTokenResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(null) // Facebook doesn't use refresh tokens
                    .expiresIn(expiresIn)
                    .tokenType("bearer")
                    .build();

        } catch (Exception e) {
            log.error("Error exchanging Facebook OAuth code", e);
            throw new PlatformIntegrationException("Facebook OAuth exchange failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException {

        log.info("Extending Facebook token for credential {}", credential.getId());

        try {
            // Facebook uses token extension (not refresh)
            // This extends the token back to 60 days
            String url = UriComponentsBuilder.fromHttpUrl(TOKEN_EXCHANGE_URL)
                    .queryParam("grant_type", "fb_exchange_token")
                    .queryParam("client_id", appId)
                    .queryParam("client_secret", appSecret)
                    .queryParam("fb_exchange_token", credential.getAccessToken())
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Token extension failed");
            }

            JsonNode tokenData = objectMapper.readTree(response.getBody());
            String newAccessToken = tokenData.get("access_token").asText();
            int expiresIn = tokenData.has("expires_in") ?
                    tokenData.get("expires_in").asInt() : 5184000; // Default 60 days

            // Update credential with extended token
            credential.setAccessToken(newAccessToken);
            credential.setTokenExpiresAt(
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn)
            );
            credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
            credential.setSyncErrorMessage(null);

            log.info("Successfully extended Facebook token for credential {} (new expiry in {} days)",
                    credential.getId(), expiresIn / 86400);

            return credential;

        } catch (HttpClientErrorException e) {
            log.error("Facebook token extension failed: {}", e.getMessage());

            // Mark credential as expired
            credential.setStatus(ChannelCredential.CredentialStatus.EXPIRED);
            credential.setSyncErrorMessage("Token extension failed. Please reconnect.");

            throw new TokenExpiredException(
                    credential.getPlatformType().name(),
                    credential.getId(),
                    "Facebook access has expired. Please reconnect.",
                    e
            );

        } catch (Exception e) {
            log.error("Unexpected error extending Facebook token", e);
            throw new PlatformIntegrationException("Token extension error: " + e.getMessage(), e);
        }
    }

    @Override
    public List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
            throws PlatformIntegrationException {

        try {
            // CRITICAL FIX: Get page access token from metadata (not user token)
            String pageAccessToken = getPageAccessTokenFromMetadata(credential);
            if (pageAccessToken == null) {
                log.error("No page access token found in metadata for credential {}", credential.getId());
                throw new PlatformIntegrationException(
                        "No page access token found. Please reconnect Facebook.");
            }

            // Get page ID from metadata
            String pageId = getPageIdFromCredential(credential);
            if (pageId == null) {
                log.warn("No Facebook page ID found in credential metadata");
                return Collections.emptyList();
            }

            log.info("Fetching Facebook reviews for page: {} using page access token", pageId);

            // Fetch ratings/reviews from Facebook Graph API using PAGE token
            List<PlatformReviewDto> apiReviews = fetchReviewsFromGraphAPI(pageAccessToken, pageId);

            log.info("Fetched {} Facebook reviews", apiReviews.size());
            return apiReviews;

        } catch (Exception e) {
            log.error("Error fetching Facebook reviews", e);
            throw new PlatformIntegrationException("Failed to fetch Facebook reviews: " + e.getMessage(), e);
        }
    }

    /**
     * CRITICAL: Get page access token from metadata
     * The page access token is required for accessing page-level resources
     */
    private String getPageAccessTokenFromMetadata(ChannelCredential credential) {
        if (credential.getMetadata() == null) {
            log.warn("Credential metadata is null");
            return null;
        }

        Object token = credential.getMetadata().get("pageAccessToken");
        if (token == null) {
            log.warn("No pageAccessToken found in metadata");
            return null;
        }

        return token.toString();
    }

    private List<PlatformReviewDto> fetchReviewsFromGraphAPI(String pageAccessToken, String pageId)
            throws PlatformIntegrationException {
        try {
            String url = String.format("%s/%s/ratings", GRAPH_API_BASE, pageId);

            // UPDATED: Now requesting reviewer fields including nested picture
            url = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("access_token", pageAccessToken)
                    .queryParam("fields", "id,created_time,rating,review_text,recommendation_type,reviewer{name,id,picture}")
                    .queryParam("limit", "100")
                    .build()
                    .toUriString();

            log.debug("Fetching Facebook ratings from: {}", url.replace(pageAccessToken, "***"));

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to fetch Facebook ratings");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.get("data");

            if (data == null || !data.isArray()) {
                log.info("No ratings data returned from Facebook");
                return Collections.emptyList();
            }

            List<PlatformReviewDto> reviews = new ArrayList<>();

            for (JsonNode rating : data) {
                try {
                    PlatformReviewDto review = parseFacebookRating(rating);
                    reviews.add(review);
                } catch (Exception e) {
                    log.warn("Failed to parse Facebook rating: {}", e.getMessage());
                }
            }

            return reviews;

        } catch (HttpClientErrorException e) {
            log.error("Facebook Graph API error: {} - {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new PlatformIntegrationException(
                    "Graph API request failed: " + e.getStatusCode() + " - " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("Unexpected error calling Graph API", e);
            throw new PlatformIntegrationException("Graph API request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate deterministic review ID from content
     * Uses SHA-256 hash of review_text + created_time
     * This ensures the same review always gets the same ID
     */
    private String generateDeterministicId(String reviewText, String createdTime) {
        try {
            String input = (reviewText + "|" + createdTime).trim();
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Convert to hex string (first 16 characters for brevity)
            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < Math.min(hash.length, 8); i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return "fb_" + hexString.toString();
        } catch (Exception e) {
            log.error("Failed to generate deterministic ID", e);
            return "fb_" + UUID.randomUUID().toString();
        }
    }

    private PlatformReviewDto parseFacebookRating(JsonNode rating) {
        // Facebook's /ratings endpoint doesn't return an 'id' field
        // Generate deterministic ID from review text + created_time to prevent duplicates
        String reviewText = rating.has("review_text") ? rating.get("review_text").asText() : "";
        String createdTime = rating.has("created_time") ? rating.get("created_time").asText() : "";
        String reviewId = generateDeterministicId(reviewText, createdTime);

        // Facebook uses recommendations (positive/negative), not star ratings
        // Map to 5-star system: positive=5, negative=1
        int ratingValue = 5; // Default to positive
        if (rating.has("recommendation_type")) {
            String recommendationType = rating.get("recommendation_type").asText();
            ratingValue = "positive".equalsIgnoreCase(recommendationType) ? 5 : 1;
        } else if (rating.has("has_recommendation")) {
            boolean hasRecommendation = rating.get("has_recommendation").asBoolean();
            ratingValue = hasRecommendation ? 5 : 1;
        }

        // Use empty string as null for consistency
        if (reviewText.isEmpty()) {
            reviewText = null;
        }

        // Parse created_time
        OffsetDateTime createdAt = OffsetDateTime.now();
        if (rating.has("created_time")) {
            try {
                String createdTimeStr = rating.get("created_time").asText();
                if (createdTimeStr.endsWith("+0000")) {
                    createdTimeStr = createdTimeStr.replace("+0000", "Z");
                }
                createdAt = OffsetDateTime.parse(createdTimeStr);
            } catch (Exception e) {
                log.error("Failed to parse Facebook timestamp: {}", e.getMessage());
            }
        }

        // Reviewer info
        String reviewerName = "Facebook User";
        String reviewerImage = null;

        if (rating.has("reviewer")) {
            JsonNode reviewer = rating.get("reviewer");

            // 1. Get Name
            if (reviewer.has("name")) {
                reviewerName = reviewer.get("name").asText();
            }

            // 2. Get Picture (Nested: picture -> data -> url)
            if (reviewer.has("picture") &&
                    reviewer.get("picture").has("data") &&
                    reviewer.get("picture").get("data").has("url")) {

                reviewerImage = reviewer.get("picture").get("data").get("url").asText();
            }
        }

        return PlatformReviewDto.builder()
                .platformReviewId(reviewId)
                .reviewerName(reviewerName)
                .reviewerPhotoUrl(reviewerImage)
                .rating(ratingValue)
                .comment(reviewText)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .isPlatformVerified(false)
                .build();
    }

    private String getPageIdFromCredential(ChannelCredential credential) {
        if (credential.getMetadata() == null) return null;
        Object pageId = credential.getMetadata().get("pageId");
        return pageId != null ? pageId.toString() : null;
    }

    @Override
    public void postReviewResponse(ChannelCredential credential, String reviewId, String responseText)
            throws PlatformIntegrationException {
        log.warn("Facebook review response not yet implemented");
        throw new PlatformIntegrationException("Review response not yet configured");
    }

    @Override
    public boolean validateCredentials(ChannelCredential credential) {
        try {
            String pageId = getPageIdFromCredential(credential);
            if (pageId == null) return false;

            // Use page access token for validation
            String pageAccessToken = getPageAccessTokenFromMetadata(credential);
            if (pageAccessToken == null) return false;

            String url = String.format("%s/%s", GRAPH_API_BASE, pageId);
            url = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("access_token", pageAccessToken)
                    .queryParam("fields", "id,name")
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            log.warn("Facebook credential validation failed: {}", e.getMessage());
            return false;
        }
    }
}