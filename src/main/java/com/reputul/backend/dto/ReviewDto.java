package com.reputul.backend.dto;

import com.reputul.backend.models.Review;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Review Data Transfer Object (DTO)
 *
 * This DTO solves the Hibernate lazy loading serialization issue by:
 * 1. Containing only the data we want to expose to the frontend
 * 2. Avoiding any Hibernate-managed relationships
 * 3. Being a plain POJO that Jackson can serialize without issues
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewDto {

    private Long id;
    private int rating;
    private String comment;
    private String source;
    private String sourceReviewId;
    private String sourceReviewUrl;
    private String reviewerPhotoUrl;
    private Boolean platformVerified;
    private String platformResponse;
    private OffsetDateTime platformResponseAt;
    private OffsetDateTime syncedAt;
    private String customerName;
    private String customerEmail;
    private OffsetDateTime createdAt;

    // Business information (flattened to avoid lazy loading)
    private Long businessId;
    private String businessName;
    private Long organizationId;

    /**
     * Convert a Review entity to ReviewDTO
     * This method safely extracts data without triggering lazy loading
     */
    public static ReviewDto fromEntity(Review review) {
        if (review == null) {
            return null;
        }

        return ReviewDto.builder()
                .id(review.getId())
                .rating(review.getRating())
                .comment(review.getComment())
                .source(review.getSource())
                .sourceReviewId(review.getSourceReviewId())
                .sourceReviewUrl(review.getSourceReviewUrl())
                .reviewerPhotoUrl(review.getReviewerPhotoUrl())
                .platformVerified(review.getPlatformVerified())
                .platformResponse(review.getPlatformResponse())
                .platformResponseAt(review.getPlatformResponseAt())
                .syncedAt(review.getSyncedAt())
                .customerName(review.getCustomerName())
                .customerEmail(review.getCustomerEmail())
                .createdAt(review.getCreatedAt())
                // Safely extract business data if available (these should be loaded within transaction)
                .businessId(review.getBusiness() != null ? review.getBusiness().getId() : null)
                .businessName(review.getBusiness() != null ? review.getBusiness().getName() : null)
                .organizationId(review.getBusiness() != null && review.getBusiness().getOrganization() != null
                        ? review.getBusiness().getOrganization().getId() : null)
                .build();
    }

    /**
     * Convert a list of Review entities to ReviewDTOs
     */
    public static List<ReviewDto> fromEntities(List<Review> reviews) {
        if (reviews == null) {
            return null;
        }

        return reviews.stream()
                .map(ReviewDto::fromEntity)
                .collect(Collectors.toList());
    }
}