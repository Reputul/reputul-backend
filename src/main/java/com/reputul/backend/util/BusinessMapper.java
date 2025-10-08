package com.reputul.backend.util;

import com.reputul.backend.dto.BusinessDto;
import com.reputul.backend.models.Business;

public class BusinessMapper {

    public static BusinessDto toDto(Business business) {
        if (business == null) {
            return null;
        }

        return BusinessDto.builder()
                .id(business.getId())
                .name(business.getName())
                .industry(business.getIndustry())
                .phone(business.getPhone())
                .website(business.getWebsite())
                .address(business.getAddress())
                .googlePlaceId(business.getGooglePlaceId())
                .facebookPageUrl(business.getFacebookPageUrl())
                .yelpPageUrl(business.getYelpPageUrl())

                // Legacy reputation score
                .reputationScore(business.getReputationScore())

                // Wilson Score metrics
                .reputulRating(business.getReputulRating())
                .reputationScoreQuality(business.getReputationScoreQuality())
                .reputationScoreVelocity(business.getReputationScoreVelocity())
                .reputationScoreResponsiveness(business.getReputationScoreResponsiveness())
                .reputationScoreComposite(business.getReputationScoreComposite())
                .lastReputationUpdate(business.getLastReputationUpdate())

                // Logo fields
                .logoFilename(business.getLogoFilename())
                .logoUrl(business.getLogoUrl())
                .logoContentType(business.getLogoContentType())
                .logoUploadedAt(business.getLogoUploadedAt())

                .badge(business.getBadge())
                .reviewPlatformsConfigured(business.getReviewPlatformsConfigured())
                .createdAt(business.getCreatedAt())
                .updatedAt(business.getUpdatedAt())

                // Computed fields using helper methods
                .publicRating(business.getPublicRating())
                .ownerScore(business.getOwnerScore())
                .reputationColorBand(business.getReputationColorBand())

                .build();
    }
}