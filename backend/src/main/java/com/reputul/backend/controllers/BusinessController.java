package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.dto.ReviewSummaryDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    public ResponseEntity<?> createBusiness(@RequestBody Business business,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

            business.setOwner(owner);
            business.setCreatedAt(LocalDateTime.now());
            Business savedBusiness = businessRepo.save(business);

            return ResponseEntity.ok(savedBusiness);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error creating business: " + e.getMessage());
        }
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
                            .reviewCount(business.getReviews() != null ? business.getReviews().size() : 0)
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

    @PutMapping("/{id}")
    public ResponseEntity<?> updateBusiness(@PathVariable Long id,
                                            @RequestBody Business updatedBusiness,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            Optional<Business> optional = businessRepo.findById(id);
            if (optional.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Business biz = optional.get();

            // Get the authenticated user
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Check ownership
            if (!biz.getOwner().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to update this business");
            }

            // Update business fields
            biz.setName(updatedBusiness.getName());
            biz.setIndustry(updatedBusiness.getIndustry());
            biz.setPhone(updatedBusiness.getPhone());
            biz.setWebsite(updatedBusiness.getWebsite());
            biz.setAddress(updatedBusiness.getAddress());

            businessRepo.save(biz);
            return ResponseEntity.ok(biz);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating business: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteBusiness(@PathVariable Long id,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            Optional<Business> optional = businessRepo.findById(id);
            if (optional.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Business biz = optional.get();

            // Get the authenticated user
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Check ownership
            if (!biz.getOwner().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to delete this business");
            }

            businessRepo.delete(biz);
            return ResponseEntity.ok().body("Business deleted successfully");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting business: " + e.getMessage());
        }
    }
}