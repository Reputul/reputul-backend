package com.reputul.backend.controllers;

import com.reputul.backend.auth.JwtUtil;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReputationService;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;
    private final ReputationService reputationService;
    private final UserRepository userRepo;
    private final JwtUtil jwtUtil;

    public ReviewController(
            ReviewRepository reviewRepo,
            BusinessRepository businessRepo,
            ReputationService reputationService,
            UserRepository userRepo,
            JwtUtil jwtUtil
    ) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
        this.reputationService = reputationService;
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
    }

    // ✅ Authenticated internal review submission (with businessId in path)
    @PostMapping("/{businessId}")
    public ResponseEntity<?> createReview(@PathVariable Long businessId,
                                          @RequestBody Review review,
                                          @AuthenticationPrincipal UserDetails userDetails) {
        try {
            Business business = businessRepo.findById(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            // Validate rating
            if (review.getRating() < 1 || review.getRating() > 5) {
                return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
            }

            review.setBusiness(business);
            review.setCreatedAt(LocalDateTime.now());

            Review savedReview = reviewRepo.save(review);
            reputationService.updateBusinessReputationAndBadge(businessId);

            return ResponseEntity.ok(savedReview);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error creating review: " + e.getMessage());
        }
    }

    // ✅ Manual review by business owner (from dashboard) - FIXED
    @PostMapping("/manual/{businessId}")
    public ResponseEntity<?> createManualReview(
            @PathVariable Long businessId,
            @RequestBody Review review,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            // Verify the user owns this business
            if (!business.getOwner().getId().equals(user.getId())) {
                return ResponseEntity.status(403).body("You don't own this business");
            }

            // Validate rating
            if (review.getRating() < 1 || review.getRating() > 5) {
                return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
            }

            review.setBusiness(business);
            review.setCreatedAt(LocalDateTime.now());
            review.setSource("manual"); // Mark as manually added

            Review savedReview = reviewRepo.save(review);
            reputationService.updateBusinessReputationAndBadge(business.getId());

            return ResponseEntity.ok(savedReview);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error adding manual review: " + e.getMessage());
        }
    }

    // ✅ Public-facing review submission (unauthenticated)
    @PostMapping("/public/{businessId}")
    public ResponseEntity<?> createPublicReview(
            @PathVariable Long businessId,
            @RequestBody Review review
    ) {
        return businessRepo.findById(businessId)
                .map(business -> {
                    if (review.getRating() < 1 || review.getRating() > 5) {
                        return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
                    }

                    review.setBusiness(business);
                    review.setCreatedAt(LocalDateTime.now());
                    review.setSource("public"); // Mark as public submission
                    reviewRepo.save(review);

                    reputationService.updateBusinessReputationAndBadge(businessId);

                    return ResponseEntity.ok("Review submitted successfully.");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ✅ All reviews for a specific business (with sorting)
    @GetMapping("/business/{businessId}")
    public List<Review> getReviewsByBusiness(
            @PathVariable Long businessId,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir
    ) {
        Sort.Direction direction = sortDir.equalsIgnoreCase("asc") ?
                Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, sortBy);

        return reviewRepo.findByBusinessIdOrderBy(businessId, sort);
    }

    // ✅ All reviews (admin/debug)
    @GetMapping
    public List<Review> getAllReviews() {
        return reviewRepo.findAll();
    }

    // ✅ Delete a review (business owner only)
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<?> deleteReview(
            @PathVariable Long reviewId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            Review review = reviewRepo.findById(reviewId)
                    .orElse(null);

            if (review == null) {
                return ResponseEntity.notFound().build();
            }

            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Check if user owns the business this review belongs to
            if (!review.getBusiness().getOwner().getId().equals(user.getId())) {
                return ResponseEntity.status(403).body("You don't have permission to delete this review");
            }

            Long businessId = review.getBusiness().getId();
            reviewRepo.delete(review);

            // Update reputation after deletion
            reputationService.updateBusinessReputationAndBadge(businessId);

            return ResponseEntity.ok("Review deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error deleting review: " + e.getMessage());
        }
    }
}