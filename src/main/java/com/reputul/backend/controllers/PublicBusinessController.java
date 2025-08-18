package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.dto.ReviewSummaryDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/public/businesses")
public class PublicBusinessController {

    private final BusinessRepository businessRepo;
    private final ReviewRepository reviewRepo;

    public PublicBusinessController(BusinessRepository businessRepo, ReviewRepository reviewRepo) {
        this.businessRepo = businessRepo;
        this.reviewRepo = reviewRepo;
    }

    /**
     * Get public business information by ID
     * This endpoint is accessible without authentication
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getPublicBusinessById(@PathVariable Long id) {
        System.out.println("=== PUBLIC ENDPOINT HIT - Business ID: " + id + " ===");

        try {
            Business business = businessRepo.findById(id).orElse(null);

            if (business == null) {
                System.out.println("❌ Business not found with ID: " + id);
                return ResponseEntity.notFound().build();
            }

            System.out.println("✅ Found business: " + business.getName());

            // Create simple response without DTO builder for now
            Map<String, Object> response = new HashMap<>();
            response.put("id", business.getId());
            response.put("name", business.getName());
            response.put("industry", business.getIndustry());
            response.put("phone", business.getPhone());
            response.put("website", business.getWebsite());
            response.put("address", business.getAddress());
            response.put("reputationScore", business.getReputationScore());
            response.put("badge", business.getBadge());
            response.put("reviewPlatformsConfigured", business.getReviewPlatformsConfigured());

            // Handle reviews safely
            try {
                int reviewCount = business.getReviews() != null ? business.getReviews().size() : 0;
                response.put("reviewCount", reviewCount);
                System.out.println("✅ Review count: " + reviewCount);
            } catch (Exception e) {
                System.out.println("⚠️ Could not load reviews: " + e.getMessage());
                response.put("reviewCount", 0);
            }

            System.out.println("✅ Returning response for business: " + business.getName());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            System.out.println("❌ Error in getPublicBusinessById: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Get public review summary for a business
     * This endpoint is accessible without authentication
     */
    @GetMapping("/{id}/review-summary")
    public ResponseEntity<?> getPublicReviewSummary(@PathVariable Long id) {
        try {
            Business business = businessRepo.findById(id).orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            double avgRating = reviewRepo.findAverageRatingByBusinessId(id) != null
                    ? reviewRepo.findAverageRatingByBusinessId(id) : 0.0;
            int total = reviewRepo.countByBusinessId(id);
            List<Review> recent = reviewRepo.findTop1ByBusinessIdOrderByCreatedAtDesc(id);
            String comment = recent.isEmpty() ? "No reviews yet." : recent.get(0).getComment();

            String badge = business.getBadge() != null ? business.getBadge() : "Unranked";

            ReviewSummaryDto summary = new ReviewSummaryDto(avgRating, total, comment, badge);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            System.out.println("❌ Error in getPublicReviewSummary: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * Get all public businesses (optional - for listing pages)
     * This endpoint is accessible without authentication
     */
    @GetMapping
    public List<Business> getAllPublicBusinesses() {
        return businessRepo.findAll();
    }

    /**
     * Get public businesses by industry (optional - for filtering)
     * This endpoint is accessible without authentication
     */
    @GetMapping("/industry/{industry}")
    public List<Business> getPublicBusinessesByIndustry(@PathVariable String industry) {
        return businessRepo.findByIndustryIgnoreCase(industry);
    }

    /**
     * Search public businesses by name (optional - for search functionality)
     * This endpoint is accessible without authentication
     */
    @GetMapping("/search")
    public List<Business> searchPublicBusinesses(@RequestParam String name) {
        return businessRepo.findByNameContainingIgnoreCase(name);
    }
}