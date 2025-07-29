package com.reputul.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateBusinessReviewPlatformsDto {
    private String googlePlaceId;
    private String facebookPageUrl;
    private String yelpPageUrl;
}