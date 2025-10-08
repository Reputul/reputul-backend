package com.reputul.backend.integrations;

import com.reputul.backend.models.ChannelCredential;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Interface for platform-specific review integrations
 * Each platform (Google, Facebook, Yelp) implements this interface
 */
public interface PlatformReviewClient {

    /**
     * Get platform type this client handles
     */
    ChannelCredential.PlatformType getPlatformType();

    /**
     * Fetch reviews from platform
     * @param credential OAuth credentials
     * @param sinceDate Only fetch reviews after this date (null = all)
     * @return List of review DTOs from platform
     */
    List<PlatformReviewDto> fetchReviews(ChannelCredential credential, OffsetDateTime sinceDate)
            throws PlatformIntegrationException;

    /**
     * Post a response to a review
     * @param credential OAuth credentials
     * @param reviewId Platform-specific review ID
     * @param responseText Business owner's response
     */
    void postReviewResponse(ChannelCredential credential, String reviewId, String responseText)
            throws PlatformIntegrationException;

    /**
     * Validate credentials by making a test API call
     */
    boolean validateCredentials(ChannelCredential credential);

    /**
     * Refresh OAuth token if expired
     */
    ChannelCredential refreshToken(ChannelCredential credential)
            throws PlatformIntegrationException;

    /**
     * Get OAuth authorization URL for initial connection
     */
    String getAuthorizationUrl(String state, String redirectUri);

    /**
     * Exchange OAuth code for access token
     */
    OAuthTokenResponse exchangeCodeForToken(String code, String redirectUri)
            throws PlatformIntegrationException;
}