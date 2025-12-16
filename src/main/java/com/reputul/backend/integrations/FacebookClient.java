package com.reputul.backend.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.exceptions.TokenExpiredException;
import com.reputul.backend.models.ChannelCredential;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Facebook Page Reviews integration
 *
 * UPDATED: Implements automatic token extension for long-lived tokens
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

    // ========================================
    // UPDATED: Automatic token extension implementation
    // ========================================

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
            // Get page ID from metadata
            String pageId = getPageIdFromCredential(credential);
            if (pageId == null) {
                log.warn("No Facebook page ID found in credential metadata");
                return Collections.emptyList();
            }

            log.info("Fetching Facebook reviews for page: {}", pageId);

            // Fetch ratings/reviews from Facebook Graph API
            List<PlatformReviewDto> apiReviews = fetchReviewsFromGraphAPI(credential.getAccessToken(), pageId);

            // Scrape reviewer names from public page (Graph API doesn't provide names)
            String pageUrl = getPageUrlFromCredential(credential);
            if (pageUrl != null) {
                enrichReviewsWithScrapedNames(apiReviews, pageUrl);
            }

            log.info("Fetched {} Facebook reviews", apiReviews.size());
            return apiReviews;

        } catch (Exception e) {
            log.error("Error fetching Facebook reviews", e);
            throw new PlatformIntegrationException("Failed to fetch Facebook reviews: " + e.getMessage(), e);
        }
    }

    private List<PlatformReviewDto> fetchReviewsFromGraphAPI(String accessToken, String pageId)
            throws PlatformIntegrationException {
        try {
            String url = String.format("%s/%s/ratings", GRAPH_API_BASE, pageId);

            url = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("access_token", accessToken)
                    .queryParam("fields", "created_time,rating,review_text,reviewer")
                    .queryParam("limit", "100")
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to fetch Facebook ratings");
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode data = root.get("data");

            if (data == null || !data.isArray()) {
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

        } catch (Exception e) {
            throw new PlatformIntegrationException("Graph API request failed: " + e.getMessage(), e);
        }
    }

    private PlatformReviewDto parseFacebookRating(JsonNode rating) {
        String reviewId = rating.has("id") ? rating.get("id").asText() : UUID.randomUUID().toString();
        int ratingValue = rating.has("rating") ? rating.get("rating").asInt() : 0;
        String reviewText = rating.has("review_text") ? rating.get("review_text").asText() : null;

        // Parse created_time
        OffsetDateTime createdAt = OffsetDateTime.now();
        if (rating.has("created_time")) {
            try {
                long timestamp = rating.get("created_time").asLong();
                createdAt = OffsetDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
            } catch (Exception e) {
                log.warn("Failed to parse Facebook timestamp");
            }
        }

        // Reviewer info (limited from Graph API)
        String reviewerName = "Facebook User"; // Default (will be enriched by scraping)
        if (rating.has("reviewer") && rating.get("reviewer").has("name")) {
            reviewerName = rating.get("reviewer").get("name").asText();
        }

        return PlatformReviewDto.builder()
                .platformReviewId(reviewId)
                .reviewerName(reviewerName)
                .rating(ratingValue)
                .comment(reviewText)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .isPlatformVerified(false)
                .build();
    }

    private void enrichReviewsWithScrapedNames(List<PlatformReviewDto> reviews, String pageUrl) {
        try {
            log.info("Scraping Facebook page for reviewer names: {}", pageUrl);

            Document doc = Jsoup.connect(pageUrl + "/reviews")
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .get();

            Elements reviewElements = doc.select("div[data-ad-comet-preview]");
            log.info("Found {} review elements on page", reviewElements.size());

            int index = 0;
            for (Element reviewEl : reviewElements) {
                if (index >= reviews.size()) break;

                String reviewerName = reviewEl.select("a[role='link'] strong").text();
                if (reviewerName != null && !reviewerName.isEmpty()) {
                    reviews.get(index).setReviewerName(reviewerName);
                    log.debug("Enriched review {} with name: {}", index, reviewerName);
                }

                index++;
            }

        } catch (Exception e) {
            log.warn("Failed to scrape Facebook reviewer names: {}", e.getMessage());
        }
    }

    private String getPageIdFromCredential(ChannelCredential credential) {
        if (credential.getMetadata() == null) return null;
        Object pageId = credential.getMetadata().get("pageId");
        return pageId != null ? pageId.toString() : null;
    }

    private String getPageUrlFromCredential(ChannelCredential credential) {
        if (credential.getMetadata() == null) return null;
        Object pageUrl = credential.getMetadata().get("pageUrl");
        return pageUrl != null ? pageUrl.toString() : null;
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

            String url = String.format("%s/%s", GRAPH_API_BASE, pageId);
            url = UriComponentsBuilder.fromHttpUrl(url)
                    .queryParam("access_token", credential.getAccessToken())
                    .queryParam("fields", "id,name")
                    .build()
                    .toUriString();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();

        } catch (Exception e) {
            return false;
        }
    }
}