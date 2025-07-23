package com.reputul.backend.controllers;

import com.reputul.backend.auth.JwtUtil;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReputationService;
import org.springframework.http.ResponseEntity;
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

    @PostMapping("/{businessId}")
    public Review createReview(@PathVariable Long businessId, @RequestBody Review review) {
        Business business = businessRepo.findById(businessId).orElseThrow();
        review.setBusiness(business);
        review.setCreatedAt(LocalDateTime.now());

        Review savedReview = reviewRepo.save(review);
        reputationService.updateBusinessReputationAndBadge(businessId);
        return savedReview;
    }

    // ✅ NEW: Manually submit a review for the authenticated owner's business
    @PostMapping("/manual")
    public ResponseEntity<?> createManualReview(@RequestBody Review review, @RequestHeader("Authorization") String authHeader) {
        String token = authHeader.replace("Bearer ", "");
        String email = jwtUtil.extractUsername(token);
        User user = userRepo.findByEmail(email).orElseThrow();

        Business business = businessRepo.findByOwnerId(user.getId())
                .stream().findFirst().orElseThrow();

        review.setBusiness(business);
        review.setCreatedAt(LocalDateTime.now());
        reviewRepo.save(review);

        reputationService.updateBusinessReputationAndBadge(business.getId());

        return ResponseEntity.ok("Review added manually.");
    }

    @GetMapping("/business/{businessId}")
    public List<Review> getReviewsByBusiness(@PathVariable Long businessId) {
        return reviewRepo.findByBusinessId(businessId);
    }

    @GetMapping
    public List<Review> getAllReviews() {
        return reviewRepo.findAll();
    }
}
