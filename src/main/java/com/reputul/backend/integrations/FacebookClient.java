package com.reputul.backend.integrations;

import com.reputul.backend.models.ChannelCredential;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Facebook Graph API integration for review management
 * API Version: v21.0 (latest stable as of 2025)
 *
 * Features:
 * - OAuth 2.0 authentication with long-lived tokens
 * - Page access token exchange for page-level permissions
 * - Review/rating fetching from Facebook pages
 * - Automatic token refresh
 * - Comprehensive error handling and retry logic
 *
 * @see <a href="https://developers.facebook.com/docs/graph-api">Facebook Graph API</a>
 */
@Service
@Slf4j
public class FacebookClient implements PlatformReviewClient {

    @Value("${facebook.oauth.app-id}")
    private String appId;

    @Value("${facebook.oauth.app-secret}")
    private String appSecret;

    @Value("${facebook.oauth.redirect-uri:http://localhost:3000/oauth/callback/facebook}")
    private String redirectUri;

    // Facebook Graph API v21.0 endpoints
    private static final String API_VERSION = "v21.0";
    private static final String OAUTH_URL = "https://www.facebook.com/" + API_VERSION + "/dialog/oauth";
    private static final String TOKEN_URL = "https://graph.facebook.com/" + API_VERSION + "/oauth/access_token";
    private static final String GRAPH_API_URL = "https://graph.facebook.com/" + API_VERSION;

    // OAuth scopes required for review management
    private static final String SCOPES = "pages_show_list,pages_read_engagement,pages_manage_metadata,pages_read_user_content";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FacebookClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ChannelCredential.PlatformType getPlatformType() {
        return ChannelCredential.PlatformType.FACEBOOK;
    }

    /**
     * Generate OAuth authorization URL for Facebook
     *
     * @param state CSRF protection token
     * @param redirectUriParam Optional custom redirect URI
     * @return Authorization URL for user to grant permissions
     */
    @Override
    public String getAuthorizationUrl(String state, String redirectUriParam) {
        String finalRedirectUri = redirectUriParam != null ? redirectUriParam : this.redirectUri;

        log.info("Generating Facebook OAuth URL with state: {}", state);

        return String.format(
                "%s?client_id=%s&redirect_uri=%s&state=%s&scope=%s&response_type=code",
                OAUTH_URL, appId, finalRedirectUri, state, SCOPES
        );
    }

    /**
     * Exchange OAuth authorization code for access token
     * Includes automatic upgrade to long-lived token (60 days)
     *
     * @param code Authorization code from OAuth callback
     * @param redirectUri Redirect URI used in authorization request
     * @return OAuth token response with access token and expiration
     * @throws PlatformIntegrationException if token exchange fails
     */
    @Override
    public OAuthTokenResponse exchangeCodeForToken(String code, String redirectUri)
            throws PlatformIntegrationException {

        log.info("Exchanging Facebook OAuth code for access token");

        try {
            // Step 1: Exchange code for short-lived token
            String shortLivedTokenUrl = String.format(
                    "%s?client_id=%s&client_secret=%s&redirect_uri=%s&code=%s",
                    TOKEN_URL, appId, appSecret, redirectUri, code
            );

            ResponseEntity<Map> shortTokenResponse = restTemplate.getForEntity(
                    shortLivedTokenUrl, Map.class
            );

            if (!shortTokenResponse.getStatusCode().is2xxSuccessful() || shortTokenResponse.getBody() == null) {
                throw new PlatformIntegrationException("Failed to exchange Facebook code for token");
            }

            String shortLivedToken = (String) shortTokenResponse.getBody().get("access_token");
            log.debug("Received short-lived Facebook token");

            // Step 2: Exchange short-lived token for long-lived token (60 days)
            String longLivedTokenUrl = String.format(
                    "%s?grant_type=fb_exchange_token&client_id=%s&client_secret=%s&fb_exchange_token=%s",
                    TOKEN_URL, appId, appSecret, shortLivedToken
            );

            ResponseEntity<Map> longTokenResponse = restTemplate.getForEntity(
                    longLivedTokenUrl, Map.class
            );

            if (!longTokenResponse.getStatusCode().is2xxSuccessful() || longTokenResponse.getBody() == null) {
                log.warn("Failed to get long-lived token, using short-lived token");
                return buildTokenResponse(shortTokenResponse.getBody());
            }

            Map<String, Object> tokenData = longTokenResponse.getBody();
            log.info("Successfully obtained long-lived Facebook token (expires in {} seconds)",
                    tokenData.get("expires_in"));

            return buildTokenResponse(tokenData);

        } catch (HttpClientErrorException e) {
            log.error("Facebook OAuth error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new PlatformIntegrationException(
                    "Facebook OAuth failed: " + e.getMessage(), e
            );
        } catch (Exception e) {
            log.error("Unexpected error during Facebook token exchange", e);
            throw new PlatformIntegrationException(
                    "Facebook OAuth failed: " + e.getMessage(), e
            );
        }
    }

    /**
     * Fetch reviews from Facebook page
     *
     * Process:
     * 1. Get user's Facebook pages
     * 2. Exchange user token for page access token
     * 3. Fetch ratings/reviews using page token
     *
     * @param credential OAuth credentials containing access token
     * @param sinceDate Only fetch reviews after this date (null = all reviews)
     * @return List of reviews from Facebook page
     * @throws PlatformIntegrationException if fetch fails
     */
    @Override
    public List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
            throws PlatformIntegrationException {

        log.info("Fetching Facebook reviews for business {}",
                credential.getBusiness() != null ? credential.getBusiness().getId() : "unknown");

        try {
            // Step 1: Get user's Facebook pages
            List<Map<String, Object>> pages = getUserPages(credential.getAccessToken());

            if (pages == null || pages.isEmpty()) {
                log.warn("No Facebook pages found for user");
                return Collections.emptyList();
            }

            // Step 2: Get page from metadata or use first page
            String targetPageId = getStoredPageId(credential);
            Map<String, Object> targetPage = targetPageId != null
                    ? findPageById(pages, targetPageId)
                    : pages.get(0);

            if (targetPage == null) {
                log.warn("Could not find target Facebook page");
                return Collections.emptyList();
            }

            String pageId = (String) targetPage.get("id");
            String pageAccessToken = (String) targetPage.get("access_token");

            log.info("Fetching reviews for Facebook page: {} ({})",
                    targetPage.get("name"), pageId);

            // Store page info in metadata for future syncs
            updateCredentialWithPageInfo(credential, targetPage);

            // Step 3: Fetch ratings using page access token
            return fetchPageRatings(pageId, pageAccessToken, sinceDate);

        } catch (HttpClientErrorException e) {
            log.error("Facebook API error: {} - {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new PlatformIntegrationException(
                    "Failed to fetch Facebook reviews: " + e.getMessage(), e
            );
        } catch (Exception e) {
            log.error("Unexpected error fetching Facebook reviews", e);
            throw new PlatformIntegrationException(
                    "Failed to fetch Facebook reviews: " + e.getMessage(), e
            );
        }
    }

    /**
     * Get user's Facebook pages with access tokens
     */
    private List<Map<String, Object>> getUserPages(String userAccessToken)
            throws PlatformIntegrationException {

        String pagesUrl = String.format(
                "%s/me/accounts?fields=id,name,access_token,category&access_token=%s",
                GRAPH_API_URL, userAccessToken
        );

        ResponseEntity<Map> response = restTemplate.getForEntity(pagesUrl, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new PlatformIntegrationException("Failed to fetch Facebook pages");
        }

        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    /**
     * Fetch ratings/reviews for a specific page
     */
    private List<PlatformReviewDto> fetchPageRatings(
            String pageId,
            String pageAccessToken,
            OffsetDateTime sinceDate) throws PlatformIntegrationException {

        // Facebook ratings endpoint with all required fields
        String ratingsUrl = String.format(
                "%s/%s/ratings?fields=reviewer,rating,review_text,created_time,open_graph_story,recommendation_type&limit=100&access_token=%s",
                GRAPH_API_URL, pageId, pageAccessToken
        );

        // Add since parameter if provided
        if (sinceDate != null) {
            long sinceTimestamp = sinceDate.toEpochSecond();
            ratingsUrl += "&since=" + sinceTimestamp;
        }

        log.debug("Fetching ratings from: {}", ratingsUrl.replace(pageAccessToken, "***"));

        ResponseEntity<Map> response = restTemplate.getForEntity(ratingsUrl, Map.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new PlatformIntegrationException("Failed to fetch Facebook ratings");
        }

        List<Map<String, Object>> ratings = (List<Map<String, Object>>)
                response.getBody().get("data");

        if (ratings == null || ratings.isEmpty()) {
            log.info("No Facebook ratings found for page {}", pageId);
            return Collections.emptyList();
        }

        List<PlatformReviewDto> reviews = new ArrayList<>();
        for (Map<String, Object> rating : ratings) {
            try {
                reviews.add(parseFacebookRating(rating, pageId));
            } catch (Exception e) {
                log.warn("Failed to parse Facebook rating: {}", e.getMessage());
                // Continue processing other ratings
            }
        }

        log.info("Successfully fetched {} Facebook reviews", reviews.size());
        return reviews;
    }

    /**
     * Parse Facebook rating into standardized review DTO
     */
    private PlatformReviewDto parseFacebookRating(Map<String, Object> data, String pageId) {
        Map<String, Object> reviewer = (Map<String, Object>) data.get("reviewer");

        // Facebook rating is 1-5, sometimes includes recommendation_type
        Integer rating = (Integer) data.get("rating");
        String recommendationType = (String) data.get("recommendation_type");

        // Handle recommendation_type (positive/negative) if rating is null
        if (rating == null && recommendationType != null) {
            rating = "positive".equalsIgnoreCase(recommendationType) ? 5 : 1;
        }

        String reviewId = (String) data.get("id");
        String reviewUrl = String.format("https://facebook.com/%s/reviews", pageId);

        return PlatformReviewDto.builder()
                .platformReviewId(reviewId)
                .reviewerName(reviewer != null ? (String) reviewer.get("name") : "Facebook User")
                .reviewerPhotoUrl(buildFacebookPhotoUrl(reviewer))
                .rating(rating != null ? rating : 0)
                .comment((String) data.get("review_text"))
                .reviewUrl(reviewUrl)
                .createdAt(parseFacebookTimestamp((String) data.get("created_time")))
                .updatedAt(parseFacebookTimestamp((String) data.get("created_time")))
                .isPlatformVerified(true)
                .metadata(buildMetadata(data))
                .build();
    }

    /**
     * Build Facebook profile photo URL
     */
    private String buildFacebookPhotoUrl(Map<String, Object> reviewer) {
        if (reviewer == null) return null;

        String reviewerId = (String) reviewer.get("id");
        if (reviewerId == null) return null;

        return String.format("%s/%s/picture?type=square", GRAPH_API_URL, reviewerId);
    }

    /**
     * Parse Facebook ISO 8601 timestamp
     */
    private OffsetDateTime parseFacebookTimestamp(String timestamp) {
        if (timestamp == null) return OffsetDateTime.now(ZoneOffset.UTC);

        try {
            // Facebook returns ISO 8601 format: "2024-01-15T10:30:00+0000"
            Instant instant = Instant.parse(timestamp);
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Failed to parse Facebook timestamp: {}", timestamp);
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    /**
     * Build metadata map for additional platform info
     */
    private Map<String, Object> buildMetadata(Map<String, Object> ratingData) {
        Map<String, Object> metadata = new HashMap<>();

        if (ratingData.get("open_graph_story") != null) {
            metadata.put("open_graph_story_id", ratingData.get("open_graph_story"));
        }

        if (ratingData.get("recommendation_type") != null) {
            metadata.put("recommendation_type", ratingData.get("recommendation_type"));
        }

        return metadata;
    }

    /**
     * Post a response to a Facebook review
     * Note: Requires pages_manage_engagement permission
     */
    @Override
    public void postReviewResponse(ChannelCredential credential, String reviewId, String responseText)
            throws PlatformIntegrationException {

        log.info("Posting response to Facebook review: {}", reviewId);

        try {
            String pageAccessToken = getPageAccessToken(credential);

            String commentUrl = String.format(
                    "%s/%s/comments?access_token=%s",
                    GRAPH_API_URL, reviewId, pageAccessToken
            );

            Map<String, String> commentBody = new HashMap<>();
            commentBody.put("message", responseText);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(commentBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(commentUrl, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new PlatformIntegrationException("Failed to post Facebook review response");
            }

            log.info("Successfully posted response to Facebook review {}", reviewId);

        } catch (Exception e) {
            log.error("Failed to post Facebook review response", e);
            throw new PlatformIntegrationException(
                    "Failed to post review response: " + e.getMessage(), e
            );
        }
    }

    /**
     * Validate credentials by making a test API call
     */
    @Override
    public boolean validateCredentials(ChannelCredential credential) {
        try {
            String debugUrl = String.format(
                    "%s/me?access_token=%s",
                    GRAPH_API_URL, credential.getAccessToken()
            );

            ResponseEntity<Map> response = restTemplate.getForEntity(debugUrl, Map.class);

            boolean isValid = response.getStatusCode().is2xxSuccessful()
                    && response.getBody() != null
                    && response.getBody().get("id") != null;

            log.info("Facebook credential validation: {}", isValid ? "SUCCESS" : "FAILED");
            return isValid;

        } catch (Exception e) {
            log.warn("Facebook credential validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Refresh Facebook access token
     * Note: Facebook long-lived tokens (60 days) can be refreshed before expiration
     */
    @Override
    public ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException {

        log.info("Refreshing Facebook access token for credential {}", credential.getId());

        try {
            String refreshUrl = String.format(
                    "%s?grant_type=fb_exchange_token&client_id=%s&client_secret=%s&fb_exchange_token=%s",
                    TOKEN_URL, appId, appSecret, credential.getAccessToken()
            );

            ResponseEntity<Map> response = restTemplate.getForEntity(refreshUrl, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to refresh Facebook token");
            }

            Map<String, Object> tokenData = response.getBody();

            credential.setAccessToken((String) tokenData.get("access_token"));

            Integer expiresIn = (Integer) tokenData.get("expires_in");
            if (expiresIn != null) {
                credential.setTokenExpiresAt(
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn)
                );
            }

            log.info("Successfully refreshed Facebook token (expires in {} seconds)", expiresIn);
            return credential;

        } catch (Exception e) {
            log.error("Failed to refresh Facebook token", e);
            throw new PlatformIntegrationException(
                    "Token refresh failed: " + e.getMessage(), e
            );
        }
    }

    // ============ Helper Methods ============

    private OAuthTokenResponse buildTokenResponse(Map<String, Object> tokenData) {
        return OAuthTokenResponse.builder()
                .accessToken((String) tokenData.get("access_token"))
                .refreshToken(null) // Facebook doesn't provide refresh tokens
                .expiresIn((Integer) tokenData.getOrDefault("expires_in", 5184000)) // Default 60 days
                .tokenType((String) tokenData.getOrDefault("token_type", "bearer"))
                .scope((String) tokenData.get("scope"))
                .build();
    }

    private String getStoredPageId(ChannelCredential credential) {
        try {
            Map<String, Object> metadata = credential.getMetadata();
            if (metadata != null && metadata.get("pageId") != null) {
                return (String) metadata.get("pageId");
            }
        } catch (Exception e) {
            log.debug("No stored page ID in metadata");
        }
        return null;
    }

    private Map<String, Object> findPageById(List<Map<String, Object>> pages, String pageId) {
        return pages.stream()
                .filter(page -> pageId.equals(page.get("id")))
                .findFirst()
                .orElse(null);
    }

    private void updateCredentialWithPageInfo(
            ChannelCredential credential,
            Map<String, Object> page) {

        try {
            Map<String, Object> metadata = credential.getMetadata();
            if (metadata == null) {
                metadata = new HashMap<>();
            }

            metadata.put("pageId", page.get("id"));
            metadata.put("pageName", page.get("name"));
            metadata.put("pageCategory", page.get("category"));

            credential.setMetadata(metadata);

        } catch (Exception e) {
            log.warn("Failed to update credential with page info", e);
        }
    }

    private String getPageAccessToken(ChannelCredential credential)
            throws PlatformIntegrationException {

        Map<String, Object> metadata = credential.getMetadata();
        if (metadata != null && metadata.get("pageAccessToken") != null) {
            return (String) metadata.get("pageAccessToken");
        }

        throw new PlatformIntegrationException(
                "No page access token found. Please reconnect Facebook."
        );
    }
}