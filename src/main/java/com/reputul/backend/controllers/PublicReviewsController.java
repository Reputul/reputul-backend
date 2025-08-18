package com.reputul.backend.controllers;

import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/public/reviews")
public class PublicReviewsController {

    private final ReviewRepository reviewRepo;

    public PublicReviewsController(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
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
     * Submit a new public review (optional - for the review form)
     * This endpoint is accessible without authentication
     */
    @PostMapping("/{businessId}")
    public ResponseEntity<?> submitPublicReview(@PathVariable Long businessId,
                                                @RequestBody Review review) {
        System.out.println("=== PUBLIC REVIEW SUBMISSION - Business ID: " + businessId + " ===");

        try {
            // Set the business ID
            review.setId(businessId);

            // Set source as manual/public
            review.setSource("manual");

            // Save the review
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