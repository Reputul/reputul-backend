package com.reputul.backend.integrations;

import com.reputul.backend.models.ChannelCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Google My Business API integration
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

    @Override
    public List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
            throws PlatformIntegrationException {

        log.warn("⚠️ USING MOCK DATA - Google tokens expire after 1 hour");
        log.info("Returning mock reviews for business {}", credential.getBusiness().getId());

        // MOCK DATA for testing the sync flow
        return Arrays.asList(
                PlatformReviewDto.builder()
                        .platformReviewId("google-mock-1")
                        .reviewerName("Michael Rodriguez")
                        .reviewerPhotoUrl("https://lh3.googleusercontent.com/a/default-user")
                        .rating(5)
                        .comment("Outstanding work! The team at Duckweed Digital was professional, efficient, and delivered exactly what we needed. Highly recommend!")
                        .createdAt(OffsetDateTime.now().minusDays(3))
                        .updatedAt(OffsetDateTime.now().minusDays(3))
                        .isPlatformVerified(true)
                        .build(),

                PlatformReviewDto.builder()
                        .platformReviewId("google-mock-2")
                        .reviewerName("Jennifer Martinez")
                        .reviewerPhotoUrl("https://lh3.googleusercontent.com/a/default-user")
                        .rating(4)
                        .comment("Great service and quick turnaround. Very satisfied with the results.")
                        .createdAt(OffsetDateTime.now().minusDays(7))
                        .updatedAt(OffsetDateTime.now().minusDays(7))
                        .isPlatformVerified(true)
                        .build(),

                PlatformReviewDto.builder()
                        .platformReviewId("google-mock-3")
                        .reviewerName("David Chen")
                        .rating(5)
                        .comment("Best in the area! Professional team and excellent communication throughout the project.")
                        .createdAt(OffsetDateTime.now().minusDays(12))
                        .updatedAt(OffsetDateTime.now().minusDays(12))
                        .isPlatformVerified(true)
                        .build()
        );
    }

//    @Override
//    public List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
//            throws PlatformIntegrationException {
//
//        log.info("Fetching Google reviews for business {}", credential.getBusiness().getId());
//
//        try {
//            // Step 1: Get account
//            String accountName = getFirstAccount(credential.getAccessToken());
//            log.info("Found Google account: {}", accountName);
//
//            // Step 2: Get locations for this account
//            List<String> locationNames = getLocations(credential.getAccessToken(), accountName);
//            log.info("Found {} Google locations", locationNames.size());
//
//            if (locationNames.isEmpty()) {
//                log.warn("No Google locations found for account {}", accountName);
//                return Collections.emptyList();
//            }
//
//            // Step 3: Fetch reviews from first location (for now)
//            String locationName = locationNames.get(0);
//            List<PlatformReviewDto> reviews = fetchLocationReviews(
//                    credential.getAccessToken(),
//                    locationName
//            );
//
//            log.info("Fetched {} reviews from Google location {}", reviews.size(), locationName);
//            return reviews;
//
//        } catch (Exception e) {
//            log.error("Error fetching Google reviews", e);
//            throw new PlatformIntegrationException("Failed to fetch Google reviews: " + e.getMessage(), e);
//        }
//    }

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
        // Google review structure:
        // {
        //   "reviewId": "...",
        //   "reviewer": { "displayName": "John Doe", "profilePhotoUrl": "..." },
        //   "starRating": "FIVE", // or "FOUR", "THREE", etc.
        //   "comment": "Great service!",
        //   "createTime": "2024-01-15T10:30:00Z",
        //   "updateTime": "2024-01-15T10:30:00Z"
        // }

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

    @Override
    public ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException {
        // TODO: Implement token refresh
        log.warn("Google token refresh not yet implemented");
        throw new PlatformIntegrationException("Token refresh not yet configured");
    }
}