package com.reputul.backend.util;

import com.reputul.backend.dto.ReviewDto;
import com.reputul.backend.models.Review;

import java.util.List;
import java.util.stream.Collectors;

public class ReviewMapper {

    public static ReviewDto toDTO(Review review) {
        return ReviewDto.builder()
                .id(review.getId())
                .rating(review.getRating())
                .comment(review.getComment())
                .source(review.getSource())
                .sourceReviewId(review.getSourceReviewId())
                .sourceReviewUrl(review.getSourceReviewUrl())
                .reviewerPhotoUrl(review.getReviewerPhotoUrl())
                .platformVerified(review.getPlatformVerified())
                .customerName(review.getCustomerName())
                .createdAt(review.getCreatedAt())
                .build();
    }

    public static List<ReviewDto> toDTOList(List<Review> reviews) {
        return reviews.stream()
                .map(ReviewMapper::toDTO)
                .collect(Collectors.toList());
    }
}