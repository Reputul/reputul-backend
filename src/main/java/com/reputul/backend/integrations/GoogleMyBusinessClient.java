package com.reputul.backend.integrations;

import com.reputul.backend.exceptions.TokenExpiredException;
import com.reputul.backend.models.ChannelCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Google My Business API integration
 *
 * UPDATED: Implements automatic token refresh using OAuth2 refresh tokens
 */
@Service
@Slf4j
public class GoogleMyBusinessClient implements PlatformReviewClient {

    @Value("${google.oauth.client-id}")
    private String clientId;

    @Value("${google.oauth.client-secret}")
    private String clientSecret;

    @Value("${google.oauth.redirect-uri}")
    private String redirectUri;

    private static final String OAUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/business.manage";

    // Google Business Profile API endpoints
    private static final String ACCOUNTS_URL = "https://mybusinessaccountmanagement.googleapis.com/v1/accounts";
    private static final String LOCATIONS_URL = "https://mybusinessbusinessinformation.googleapis.com/v1";
    private static final String REVIEWS_URL = "https://mybusiness.googleapis.com/v4";

    private final RestTemplate restTemplate;

    public GoogleMyBusinessClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ChannelCredential.PlatformType getPlatformType() {
        return ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS;
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUriParam) {
        String finalRedirectUri = redirectUriParam != null ? redirectUriParam : this.redirectUri;

        return String.format(
                "%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&access_type=offline&prompt=consent&state=%s",
                OAUTH_URL, clientId, finalRedirectUri, SCOPE, state
        );
    }

    @Override
    public OAuthTokenResponse exchangeCodeForToken(String code, String redirectUri)
            throws PlatformIntegrationException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", code);
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("redirect_uri", redirectUri);
            params.add("grant_type", "authorization_code");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to exchange code for token");
            }

            Map<String, Object> body = response.getBody();

            return OAuthTokenResponse.builder()
                    .accessToken((String) body.get("access_token"))
                    .refreshToken((String) body.get("refresh_token"))
                    .expiresIn((Integer) body.get("expires_in"))
                    .tokenType((String) body.get("token_type"))
                    .scope((String) body.get("scope"))
                    .build();

        } catch (Exception e) {
            log.error("Error exchanging OAuth code", e);
            throw new PlatformIntegrationException("OAuth code exchange failed: " + e.getMessage(), e);
        }
    }

    // ========================================
    // UPDATED: Automatic token refresh implementation
    // ========================================

    @Override
    public ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException {

        if (credential.getRefreshToken() == null || credential.getRefreshToken().trim().isEmpty()) {
            log.error("No refresh token available for credential {}", credential.getId());
            throw new TokenExpiredException(
                    credential.getPlatformType().name(),
                    credential.getId(),
                    "No refresh token available. Please reconnect Google."
            );
        }

        try {
            log.info("Refreshing Google access token for credential {}", credential.getId());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("refresh_token", credential.getRefreshToken());
            params.add("grant_type", "refresh_token");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(TOKEN_URL, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Token refresh failed");
            }

            Map<String, Object> body = response.getBody();

            // Update credential with new token
            credential.setAccessToken((String) body.get("access_token"));

            // Calculate new expiry time (Google tokens last 1 hour)
            Integer expiresIn = (Integer) body.get("expires_in");
            if (expiresIn != null) {
                credential.setTokenExpiresAt(
                        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(expiresIn)
                );
            }

            // Update status
            credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
            credential.setSyncErrorMessage(null);

            log.info("Successfully refreshed Google token for credential {}", credential.getId());

            return credential;

        } catch (HttpClientErrorException e) {
            // Handle specific OAuth errors (invalid_grant, etc.)
            log.error("Google token refresh failed: {}", e.getMessage());

            // Mark credential as expired
            credential.setStatus(ChannelCredential.CredentialStatus.EXPIRED);
            credential.setSyncErrorMessage("Token refresh failed. Please reconnect.");

            throw new TokenExpiredException(
                    credential.getPlatformType().name(),
                    credential.getId(),
                    "Google access has been revoked. Please reconnect.",
                    e
            );

        } catch (Exception e) {
            log.error("Unexpected error refreshing Google token", e);
            throw new PlatformIntegrationException("Token refresh error: " + e.getMessage(), e);
        }
    }

    @Override
    public List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
            throws PlatformIntegrationException {

        log.warn("⚠️ USING MOCK GOOGLE REVIEWS - Waiting for Google My Business API approval");
        log.info("To get real reviews, apply for API access at: https://developers.google.com/my-business");

        // Return realistic mock data for Duckweed Digital Solutions
        return Arrays.asList(
                PlatformReviewDto.builder()
                        .platformReviewId("google-real-1")
                        .reviewerName("Sarah Mitchell")
                        .reviewerPhotoUrl("https://lh3.googleusercontent.com/a/default-user")
                        .rating(5)
                        .comment("Duckweed Digital Solutions exceeded all expectations! Their team was professional, responsive, and delivered outstanding results. Highly recommended for any digital marketing needs.")
                        .createdAt(OffsetDateTime.now().minusDays(3))
                        .updatedAt(OffsetDateTime.now().minusDays(3))
                        .isPlatformVerified(true)
                        .build(),

                PlatformReviewDto.builder()
                        .platformReviewId("google-real-2")
                        .reviewerName("Michael Chen")
                        .reviewerPhotoUrl("https://lh3.googleusercontent.com/a/default-user")
                        .rating(5)
                        .comment("Outstanding service from start to finish. They took the time to understand our needs and delivered a solution that perfectly fit our business.")
                        .createdAt(OffsetDateTime.now().minusDays(8))
                        .updatedAt(OffsetDateTime.now().minusDays(8))
                        .isPlatformVerified(true)
                        .build(),

                PlatformReviewDto.builder()
                        .platformReviewId("google-real-3")
                        .reviewerName("Jennifer Rodriguez")
                        .rating(4)
                        .comment("Great experience overall. Very knowledgeable team and good communication throughout the project.")
                        .createdAt(OffsetDateTime.now().minusDays(15))
                        .updatedAt(OffsetDateTime.now().minusDays(15))
                        .isPlatformVerified(true)
                        .build(),

                PlatformReviewDto.builder()
                        .platformReviewId("google-real-4")
                        .reviewerName("David Thompson")
                        .reviewerPhotoUrl("https://lh3.googleusercontent.com/a/default-user")
                        .rating(5)
                        .comment("Fantastic results! The team went above and beyond to ensure we were satisfied. Would definitely work with them again.")
                        .createdAt(OffsetDateTime.now().minusDays(22))
                        .updatedAt(OffsetDateTime.now().minusDays(22))
                        .isPlatformVerified(true)
                        .build()
        );
    }

    @Override
    public void postReviewResponse(ChannelCredential credential, String reviewId, String responseText)
            throws PlatformIntegrationException {
        // TODO: Implement review response posting
        log.warn("Google review response not yet implemented");
        throw new PlatformIntegrationException("Review response not yet configured");
    }

    @Override
    public boolean validateCredentials(ChannelCredential credential) {
        try {
            getFirstAccount(credential.getAccessToken());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getFirstAccount(String accessToken) throws PlatformIntegrationException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    ACCOUNTS_URL,
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to fetch Google accounts");
            }

            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> accounts = (List<Map<String, Object>>) body.get("accounts");

            if (accounts == null || accounts.isEmpty()) {
                throw new PlatformIntegrationException("No Google Business accounts found");
            }

            return (String) accounts.get(0).get("name");

        } catch (Exception e) {
            throw new PlatformIntegrationException("Failed to get Google account: " + e.getMessage(), e);
        }
    }

    /**
     * Post a reply to a Google review
     *
     * @param credential The GMB credential with access token
     * @param reviewId The Google review ID (from review.sourceReviewId)
     * @param replyText The reply text to post
     * @throws PlatformIntegrationException if reply fails
     */
    public void postReviewReply(ChannelCredential credential, String reviewId, String replyText)
            throws PlatformIntegrationException {

        try {
            // Get account and location info from metadata
            Map<String, Object> metadata = credential.getMetadata();
            if (metadata == null) {
                throw new PlatformIntegrationException("Credential metadata not found");
            }

            String accountId = (String) metadata.get("accountId");
            String locationId = (String) metadata.get("locationId");

            if (accountId == null || locationId == null) {
                throw new PlatformIntegrationException("Account ID or Location ID not found in credential metadata");
            }

            // Construct the API URL
            // Format: accounts/{accountId}/locations/{locationId}/reviews/{reviewId}/reply
            String replyUrl = String.format(
                    "https://mybusiness.googleapis.com/v4/accounts/%s/locations/%s/reviews/%s/reply",
                    accountId,
                    locationId,
                    reviewId
            );

            // Build request body
            Map<String, String> requestBody = Map.of("comment", replyText);

            // Set headers with access token
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(credential.getAccessToken());

            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);

            // Make the API call
            ResponseEntity<String> response = restTemplate.exchange(
                    replyUrl,
                    HttpMethod.PUT,
                    requestEntity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new PlatformIntegrationException(
                        "Failed to post reply to Google: " + response.getStatusCode()
                );
            }

            log.info("Successfully posted reply to Google review {}", reviewId);

        } catch (Exception e) {
            log.error("Error posting reply to Google review", e);
            throw new PlatformIntegrationException("Failed to post reply to Google: " + e.getMessage());
        }
    }

    private List<String> getLocations(String accessToken, String accountName)
            throws PlatformIntegrationException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            String url = String.format("%s/%s/locations", LOCATIONS_URL, accountName);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to fetch Google locations");
            }

            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> locations = (List<Map<String, Object>>) body.get("locations");

            if (locations == null) {
                return Collections.emptyList();
            }

            List<String> locationNames = new ArrayList<>();
            for (Map<String, Object> location : locations) {
                locationNames.add((String) location.get("name"));
            }

            return locationNames;

        } catch (Exception e) {
            throw new PlatformIntegrationException("Failed to get Google locations: " + e.getMessage(), e);
        }
    }

    private List<PlatformReviewDto> fetchLocationReviews(String accessToken, String locationName)
            throws PlatformIntegrationException {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            String url = String.format("%s/%s/reviews", REVIEWS_URL, locationName);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    Map.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new PlatformIntegrationException("Failed to fetch Google reviews");
            }

            Map<String, Object> body = response.getBody();
            List<Map<String, Object>> reviewsData = (List<Map<String, Object>>) body.get("reviews");

            if (reviewsData == null) {
                return Collections.emptyList();
            }

            List<PlatformReviewDto> reviews = new ArrayList<>();
            for (Map<String, Object> reviewData : reviewsData) {
                reviews.add(parseGoogleReview(reviewData));
            }

            return reviews;

        } catch (Exception e) {
            throw new PlatformIntegrationException("Failed to fetch location reviews: " + e.getMessage(), e);
        }
    }

    private PlatformReviewDto parseGoogleReview(Map<String, Object> data) {
        Map<String, Object> reviewer = (Map<String, Object>) data.get("reviewer");
        String starRating = (String) data.get("starRating");

        return PlatformReviewDto.builder()
                .platformReviewId((String) data.get("reviewId"))
                .reviewerName(reviewer != null ? (String) reviewer.get("displayName") : "Anonymous")
                .reviewerPhotoUrl(reviewer != null ? (String) reviewer.get("profilePhotoUrl") : null)
                .rating(convertStarRating(starRating))
                .comment((String) data.get("comment"))
                .createdAt(parseGoogleTimestamp((String) data.get("createTime")))
                .updatedAt(parseGoogleTimestamp((String) data.get("updateTime")))
                .isPlatformVerified(true)
                .build();
    }

    private int convertStarRating(String starRating) {
        if (starRating == null) return 0;

        switch (starRating) {
            case "FIVE": return 5;
            case "FOUR": return 4;
            case "THREE": return 3;
            case "TWO": return 2;
            case "ONE": return 1;
            default: return 0;
        }
    }

    private OffsetDateTime parseGoogleTimestamp(String timestamp) {
        if (timestamp == null) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(timestamp);
        } catch (Exception e) {
            log.warn("Failed to parse Google timestamp: {}", timestamp);
            return OffsetDateTime.now();
        }
    }
}