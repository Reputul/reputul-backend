package com.reputul.backend.integrations;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class PlatformReviewDto {
    private String platformReviewId;
    private String reviewerName;
    private String reviewerPhotoUrl;
    private Integer rating; // 1-5
    private String comment;
    private String reviewUrl;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Boolean isPlatformVerified;
    private String businessResponse;
    private OffsetDateTime businessResponseAt;
    private Map<String, Object> metadata; // Platform-specific data
}