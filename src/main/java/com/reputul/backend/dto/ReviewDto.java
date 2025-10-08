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
public class ReviewDto {
    private Long id;
    private int rating;
    private String comment;
    private String source;
    private String sourceReviewId;
    private String sourceReviewUrl;
    private String reviewerPhotoUrl;
    private Boolean platformVerified;
    private String customerName;  // This is the reviewer name
    private OffsetDateTime createdAt;
}