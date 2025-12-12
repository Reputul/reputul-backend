package com.reputul.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * DTO for updating business review platform configuration
 *
 * UPDATED: Supports both Place ID and g.page short URL
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateBusinessReviewPlatformsDto {

    /**
     * Google Place ID (manually entered)
     * Example: ChIJN1t_tDeuEmsRUsoyG83frY4
     */
    private String googlePlaceId;

    /**
     * NEW: Google g.page short URL
     * Example: https://g.page/r/CZfH8POGJQGsEAI/review
     */
    private String googleReviewShortUrl;

    /**
     * Facebook page URL
     * Example: https://www.facebook.com/YourBusinessPage
     */
    private String facebookPageUrl;

    /**
     * Yelp page URL
     * Example: https://www.yelp.com/biz/your-business-name
     */
    private String yelpPageUrl;
}