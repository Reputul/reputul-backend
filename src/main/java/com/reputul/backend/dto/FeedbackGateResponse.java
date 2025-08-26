package com.reputul.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class FeedbackGateResponse {

    // Routing decision
    private String routingDecision; // "PUBLIC_REVIEWS" or "PRIVATE_FEEDBACK"

    // Customer and business info
    private Long customerId;
    private String customerName;
    private String businessName;

    // Links for routing
    private String googleReviewUrl;
    private String facebookReviewUrl;
    private String yelpReviewUrl;
    private String privateFeedbackUrl;

    // UI messages
    private String message;
    private String thankYouMessage;

    // Additional context
    private Integer submittedRating;
    private Boolean success;

    // Convenient method to check routing
    public boolean shouldRouteToPublicReviews() {
        return "PUBLIC_REVIEWS".equals(routingDecision);
    }

    public boolean shouldRouteToPrivateFeedback() {
        return "PRIVATE_FEEDBACK".equals(routingDecision);
    }
}