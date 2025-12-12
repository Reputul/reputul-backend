package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Business Data Transfer Object
 *
 * UPDATED: Added Google Places auto-detection fields
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessDto {
    private Long id;
    private String name;
    private String industry;
    private String phone;
    private String website;
    private String address;

    // ===== Google Places fields =====
    private String googlePlaceId;
    private String googleReviewUrl; // NEW: Direct review URL (https://search.google.com/local/writereview?placeid=X)
    private String googleReviewShortUrl; // NEW: g.page short URL (https://g.page/r/XXX/review)
    private String googleSearchUrl; // NEW: Fallback search URL
    private String googlePlaceName; // NEW: Name from Google Places API
    private String googlePlaceFormattedAddress; // NEW: Address from Google Places API
    private Boolean googlePlaceAutoDetected; // NEW: TRUE if auto-detected, FALSE if manual

    // ===== Other platforms =====
    private String facebookPageUrl;
    private String yelpPageUrl;

    // Legacy reputation score
    private Double reputationScore;

    // Wilson Score metrics
    private Double reputulRating;
    private Double reputationScoreQuality;
    private Double reputationScoreVelocity;
    private Double reputationScoreResponsiveness;
    private Double reputationScoreComposite;
    private OffsetDateTime lastReputationUpdate;

    // Logo fields
    private String logoFilename;
    private String logoUrl;
    private String logoContentType;
    private OffsetDateTime logoUploadedAt;

    private String badge;
    private Boolean reviewPlatformsConfigured;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Computed fields
    private Double publicRating;
    private Double ownerScore;
    private String reputationColorBand;
}