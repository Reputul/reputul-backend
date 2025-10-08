package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

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
    private String googlePlaceId;
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