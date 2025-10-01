package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/public/reviews")
public class PublicReviewsController {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;

    public PublicReviewsController(ReviewRepository reviewRepo,
                                   BusinessRepository businessRepo) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
    }

    /**
     * Get all public reviews for a business
     * This endpoint is accessible without authentication
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<Review>> getPublicReviewsByBusinessId(@PathVariable Long businessId) {
        System.out.println("=== PUBLIC REVIEWS ENDPOINT HIT - Business ID: " + businessId + " ===");

        try {
            List<Review> reviews = reviewRepo.findByBusinessIdOrderByCreatedAtDesc(businessId);
            System.out.println("✅ Found " + reviews.size() + " reviews for business " + businessId);
            return ResponseEntity.ok(reviews);
        } catch (Exception e) {
            System.out.println("❌ Error fetching reviews: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Submit a new public review (anonymous public form)
     * This endpoint is accessible without authentication
     */
    @PostMapping("/{businessId}")
    public ResponseEntity<?> submitPublicReview(@PathVariable Long businessId,
                                                @RequestBody Review review) {
        System.out.println("=== PUBLIC REVIEW SUBMISSION - Business ID: " + businessId + " ===");

        try {
            // --- Validate input (basic) ---
            Integer rating = review.getRating();
            if (rating == null || rating < 1 || rating > 5) {
                return ResponseEntity.badRequest().body("Rating must be an integer between 1 and 5.");
            }
            String comment = review.getComment();
            if (comment == null || comment.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Comment is required.");
            }
            if (comment.length() > 4000) { // guardrail
                return ResponseEntity.badRequest().body("Comment is too long.");
            }

            // --- Load business and associate (BUGFIX) ---
            Optional<Business> businessOpt = businessRepo.findById(businessId);
            if (businessOpt.isEmpty()) {
                return ResponseEntity.status(404).body("Business not found.");
            }
            Business business = businessOpt.get();

            // Make sure we're not re-using any client-provided ID
            review.setId(null);
            review.setBusiness(business);

            // Mark public/manual source explicitly (PrePersist defaults to "manual" if null)
            // Keeping "public" helps analytics & moderation distinguish sources.
            review.setSource("public");

            Review savedReview = reviewRepo.save(review);
            System.out.println("✅ Review saved with ID: " + savedReview.getId());

            return ResponseEntity.ok(savedReview);
        } catch (Exception e) {
            System.out.println("❌ Error saving review: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error saving review: " + e.getMessage());
        }
    }
}
