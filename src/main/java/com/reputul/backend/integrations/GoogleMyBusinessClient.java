package com.reputul.backend.integrations;

import com.reputul.backend.models.ChannelCredential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google My Business API integration
 * TODO: Implement actual Google API calls when ready
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
    private static final String SCOPE = "https://www.googleapis.com/auth/business.manage";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final RestTemplate restTemplate;  // ← ADD THIS

    // ADD THIS CONSTRUCTOR
    public GoogleMyBusinessClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public ChannelCredential.PlatformType getPlatformType() {
        return ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS;
    }

    @Override
    public String getAuthorizationUrl(String state, String redirectUriParam) {
        // Use the redirectUriParam that was passed in, or fall back to configured one
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

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    TOKEN_URL,
                    request,
                    Map.class
            );

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
        // TODO: Implement actual Google My Business API call
        log.info("Fetching Google reviews (stub implementation)");
        return new ArrayList<>();
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
        // TODO: Implement credential validation
        log.info("Validating Google credentials (stub)");
        return false;
    }

    @Override
    public ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException {
        // TODO: Implement token refresh
        log.warn("Google token refresh not yet implemented");
        throw new PlatformIntegrationException("Token refresh not yet configured");
    }
}