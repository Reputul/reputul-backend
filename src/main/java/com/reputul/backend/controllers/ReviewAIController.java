package com.reputul.backend.controllers;

import com.reputul.backend.dto.GenerateReplyRequest;
import com.reputul.backend.dto.GenerateReplyResponse;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ClaudeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * API endpoint for AI-powered review reply generation using Claude
 * UPDATED: Now uses ClaudeService (Anthropic) instead of OpenAI
 */
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Slf4j
public class ReviewAIController {

    private final ClaudeService claudeService; // CHANGED: From OpenAIService to ClaudeService
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;

    /**
     * Generate AI-powered reply for a review using Claude
     * POST /api/v1/reviews/generate-reply
     */
    @PostMapping("/generate-reply")
    public ResponseEntity<?> generateReply(
            @Valid @RequestBody GenerateReplyRequest request,
            Authentication authentication
    ) {
        try {
            // Get current user
            User user = userRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Get review and verify it belongs to user's organization
            Optional<Review> reviewOpt = reviewRepository.findById(request.getReviewId());

            if (reviewOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Review not found"));
            }

            Review review = reviewOpt.get();
            Business business = review.getBusiness();

            // Verify the review belongs to the user's organization
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to access this review"));
            }

            // Generate AI reply using Claude
            String aiReply = claudeService.generateReviewReply(
                    request.getReviewText() != null ? request.getReviewText() : review.getComment(),
                    request.getRating() != null ? request.getRating() : review.getRating(),
                    request.getReviewerName() != null ? request.getReviewerName() : review.getCustomerName(),
                    request.getBusinessName()
            );

            // Determine tone based on rating
            String tone = (request.getRating() != null ? request.getRating() : review.getRating()) >= 4
                    ? "professional"
                    : "apologetic";

            GenerateReplyResponse response = GenerateReplyResponse.builder()
                    .reply(aiReply)
                    .tone(tone)
                    .reviewId(request.getReviewId())
                    .build();

            log.info("Successfully generated Claude AI reply for review ID: {}", request.getReviewId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error generating Claude AI reply: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate reply. Please try again."));
        }
    }
}