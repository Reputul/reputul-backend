package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.dto.ReviewSummaryDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessRepository businessRepo;
    private final UserRepository userRepo;
    private final ReviewRepository reviewRepo;

    public BusinessController(BusinessRepository businessRepo, UserRepository userRepo, ReviewRepository reviewRepo) {
        this.businessRepo = businessRepo;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
    }

    @GetMapping
    public List<Business> getAll() {
        return businessRepo.findAll();
    }

    @PostMapping
    public Business createBusiness(@RequestBody Business business,
                                   @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User owner = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        business.setOwner(owner);
        business.setCreatedAt(LocalDateTime.now());
        return businessRepo.save(business);
    }

    @GetMapping("/user/{userId}")
    public List<Business> getByUser(@PathVariable Long userId) {
        return businessRepo.findByOwnerId(userId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBusinessById(@PathVariable Long id) {
        return businessRepo.findById(id)
                .map(business -> {
                    BusinessResponseDto response = BusinessResponseDto.builder()
                            .id(business.getId())
                            .name(business.getName())
                            .industry(business.getIndustry())
                            .phone(business.getPhone())
                            .website(business.getWebsite())
                            .address(business.getAddress())
                            .reputationScore(business.getReputationScore())
                            .badge(business.getBadge())
                            .build();
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/review-summary")
    public ResponseEntity<?> getReviewSummary(@PathVariable Long id) {
        Business business = businessRepo.findById(id)
                .orElse(null);

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
    }
}
