package com.reputul.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessReviewPlatformsDto {
    private Long businessId;
    private String businessName;
    private String googlePlaceId;
    private String facebookPageUrl;
    private String yelpPageUrl;
    private Boolean reviewPlatformsConfigured;

    // Helper methods for generating review URLs
    public String getGoogleReviewUrl() {
        if (googlePlaceId != null && !googlePlaceId.trim().isEmpty()) {
            return "https://search.google.com/local/writereview?placeid=" + googlePlaceId.trim();
        }
        return null;
    }

    public String getFacebookReviewUrl() {
        if (facebookPageUrl != null && !facebookPageUrl.trim().isEmpty()) {
            String url = facebookPageUrl.trim();
            // Ensure URL ends with /reviews/
            if (!url.endsWith("/")) {
                url += "/";
            }
            if (!url.endsWith("reviews/")) {
                url += "reviews/";
            }
            return url;
        }
        return null;
    }

    public String getYelpReviewUrl() {
        if (yelpPageUrl != null && !yelpPageUrl.trim().isEmpty()) {
            String url = yelpPageUrl.trim();
            // Yelp review URLs typically end with #reviews or writeareview
            if (!url.contains("#") && !url.contains("writeareview")) {
                if (!url.endsWith("/")) {
                    url += "/";
                }
                url += "writeareview";
            }
            return url;
        }
        return null;
    }
}