package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.dto.BusinessReviewPlatformsDto;
import com.reputul.backend.dto.ReviewSummaryDto;
import com.reputul.backend.dto.UpdateBusinessReviewPlatformsDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

            business.setUser(owner);
            business.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            Business savedBusiness = businessRepo.save(business);

            return ResponseEntity.ok(savedBusiness);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error creating business: " + e.getMessage());
        }
    }

    @GetMapping("/user/{userId}")
    public List<Business> getByUser(@PathVariable Long userId) {
        return businessRepo.findByUserId(userId);
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
                            .reviewPlatformsConfigured(business.getReviewPlatformsConfigured())
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
        long total = reviewRepo.countByBusinessId(id);
        List<Review> recent = reviewRepo.findTop1ByBusinessIdOrderByCreatedAtDesc(id);
        String comment = recent.isEmpty() ? "No reviews yet." : recent.get(0).getComment();

        String badge = business.getBadge() != null ? business.getBadge() : "Unranked";

        ReviewSummaryDto summary = new ReviewSummaryDto(avgRating, (int) total, comment, badge);
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
            if (!biz.getUser().getId().equals(user.getId())) {
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
            if (!biz.getUser().getId().equals(user.getId())) {
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

    @GetMapping("/{businessId}/review-platforms")
    public ResponseEntity<BusinessReviewPlatformsDto> getBusinessReviewPlatforms(
            @PathVariable Long businessId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Verify business belongs to user
            Business business = businessRepo.findByIdAndUserId(businessId, user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

            BusinessReviewPlatformsDto dto = BusinessReviewPlatformsDto.builder()
                    .businessId(business.getId())
                    .businessName(business.getName())
                    .googlePlaceId(business.getGooglePlaceId())
                    .facebookPageUrl(business.getFacebookPageUrl())
                    .yelpPageUrl(business.getYelpPageUrl())
                    .reviewPlatformsConfigured(business.getReviewPlatformsConfigured())
                    .build();

            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{businessId}/review-platforms")
    public ResponseEntity<BusinessReviewPlatformsDto> updateBusinessReviewPlatforms(
            @PathVariable Long businessId,
            @RequestBody UpdateBusinessReviewPlatformsDto request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Verify business belongs to user
            Business business = businessRepo.findByIdAndUserId(businessId, user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

            // Update review platform information
            business.setGooglePlaceId(request.getGooglePlaceId());
            business.setFacebookPageUrl(request.getFacebookPageUrl());
            business.setYelpPageUrl(request.getYelpPageUrl());

            // Mark as configured if at least one platform is set
            boolean isConfigured = (request.getGooglePlaceId() != null && !request.getGooglePlaceId().trim().isEmpty()) ||
                    (request.getFacebookPageUrl() != null && !request.getFacebookPageUrl().trim().isEmpty()) ||
                    (request.getYelpPageUrl() != null && !request.getYelpPageUrl().trim().isEmpty());

            business.setReviewPlatformsConfigured(isConfigured);

            Business savedBusiness = businessRepo.save(business);

            BusinessReviewPlatformsDto dto = BusinessReviewPlatformsDto.builder()
                    .businessId(savedBusiness.getId())
                    .businessName(savedBusiness.getName())
                    .googlePlaceId(savedBusiness.getGooglePlaceId())
                    .facebookPageUrl(savedBusiness.getFacebookPageUrl())
                    .yelpPageUrl(savedBusiness.getYelpPageUrl())
                    .reviewPlatformsConfigured(savedBusiness.getReviewPlatformsConfigured())
                    .build();

            return ResponseEntity.ok(dto);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{businessId}/review-platforms/validate")
    public ResponseEntity<Map<String, Object>> validateReviewPlatforms(
            @PathVariable Long businessId,
            @RequestBody UpdateBusinessReviewPlatformsDto request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Verify business belongs to user
            businessRepo.findByIdAndUserId(businessId, user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

            Map<String, Object> validation = new HashMap<>();

            // Validate Google Place ID format
            if (request.getGooglePlaceId() != null && !request.getGooglePlaceId().trim().isEmpty()) {
                String placeId = request.getGooglePlaceId().trim();
                boolean validGoogleId = placeId.length() > 10; // Basic validation
                validation.put("googleValid", validGoogleId);
                if (validGoogleId) {
                    validation.put("googleReviewUrl", "https://search.google.com/local/writereview?placeid=" + placeId);
                }
            }

            // Validate Facebook URL format
            if (request.getFacebookPageUrl() != null && !request.getFacebookPageUrl().trim().isEmpty()) {
                String fbUrl = request.getFacebookPageUrl().trim();
                boolean validFacebookUrl = fbUrl.contains("facebook.com/");
                validation.put("facebookValid", validFacebookUrl);
                if (validFacebookUrl) {
                    if (!fbUrl.endsWith("/")) fbUrl += "/";
                    if (!fbUrl.endsWith("reviews/")) fbUrl += "reviews/";
                    validation.put("facebookReviewUrl", fbUrl);
                }
            }

            // Validate Yelp URL format
            if (request.getYelpPageUrl() != null && !request.getYelpPageUrl().trim().isEmpty()) {
                String yelpUrl = request.getYelpPageUrl().trim();
                boolean validYelpUrl = yelpUrl.contains("yelp.com/");
                validation.put("yelpValid", validYelpUrl);
                if (validYelpUrl) {
                    if (!yelpUrl.contains("#") && !yelpUrl.contains("writeareview")) {
                        if (!yelpUrl.endsWith("/")) yelpUrl += "/";
                        yelpUrl += "writeareview";
                    }
                    validation.put("yelpReviewUrl", yelpUrl);
                }
            }

            return ResponseEntity.ok(validation);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}