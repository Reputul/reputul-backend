package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;

    public ReviewController(ReviewRepository reviewRepo, BusinessRepository businessRepo) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
    }

    @PostMapping("/{businessId}")
    public Review createReview(@PathVariable Long businessId, @RequestBody Review review) {
        Business business = businessRepo.findById(businessId).orElseThrow();
        review.setBusiness(business);
        review.setCreatedAt(LocalDateTime.now());
        return reviewRepo.save(review);
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
