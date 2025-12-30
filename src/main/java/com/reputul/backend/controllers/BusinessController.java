package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessDto;
import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.dto.ReviewSummaryDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.BusinessService;
import com.reputul.backend.util.BusinessMapper;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.reputul.backend.dto.UpdateBusinessReviewPlatformsDto;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    private final BusinessRepository businessRepo;
    private final BusinessService businessService;
    private final UserRepository userRepo;
    private final ReviewRepository reviewRepo;

    private static final List<String> ALLOWED_CONTENT_TYPES = Arrays.asList(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/svg+xml",
            "image/webp"
    );
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    public BusinessController(BusinessRepository businessRepo, BusinessService businessService,
                              UserRepository userRepo, ReviewRepository reviewRepo) {
        this.businessRepo = businessRepo;
        this.businessService = businessService;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
    }

    @GetMapping
    public ResponseEntity<List<BusinessDto>> getAll(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<Business> businesses = businessRepo.findByUserId(user.getId());

            // Convert to DTOs
            List<BusinessDto> dtos = businesses.stream()
                    .map(BusinessMapper::toDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping
    public ResponseEntity<?> createBusiness(@RequestBody Business business,
                                            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User owner = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

            business.setUser(owner);
            business.setOrganization(owner.getOrganization());
            business.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            Business savedBusiness = businessRepo.save(business);

            // Return DTO instead of entity
            return ResponseEntity.ok(BusinessMapper.toDto(savedBusiness));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error creating business: " + e.getMessage()));
        }
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<BusinessDto>> getByUser(@PathVariable Long userId) {
        List<Business> businesses = businessRepo.findByUserId(userId);

        List<BusinessDto> dtos = businesses.stream()
                .map(BusinessMapper::toDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
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
                            .reviewCount(Math.toIntExact(reviewRepo.countByBusinessId(business.getId()))) // FIXED: Safe long to int conversion
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
                        .body(Map.of("error", "You don't have permission to update this business"));
            }

            // Update business fields
            biz.setName(updatedBusiness.getName());
            biz.setIndustry(updatedBusiness.getIndustry());
            biz.setPhone(updatedBusiness.getPhone());
            biz.setWebsite(updatedBusiness.getWebsite());
            biz.setAddress(updatedBusiness.getAddress());

            businessRepo.save(biz);

            // Return DTO
            return ResponseEntity.ok(BusinessMapper.toDto(biz));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error updating business: " + e.getMessage()));
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

            Business business = optional.get();

            // Get the authenticated user
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Check ownership
            if (!business.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to delete this business"));
            }

            businessRepo.delete(business);
            return ResponseEntity.ok(Map.of("message", "Business deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error deleting business: " + e.getMessage()));
        }
    }

    @PostMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadBusinessLogo(
            @PathVariable Long id,
            @RequestParam("file") @NotNull MultipartFile file,
            Authentication authentication) {

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "File is empty"));
            }

            if (file.getSize() > MAX_FILE_SIZE) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "File size exceeds 5MB limit"));
            }

            String contentType = file.getContentType();
            if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid file type. Allowed: JPG, PNG, SVG, WebP"));
            }

            Business business = businessService.updateLogo(id, file, authentication);

            // Return DTO
            return ResponseEntity.ok(BusinessMapper.toDto(business));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload logo: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}/logo")
    public ResponseEntity<?> deleteBusinessLogo(
            @PathVariable Long id,
            Authentication authentication) {
        try {
            businessService.deleteLogo(id, authentication);
            return ResponseEntity.ok(Map.of("message", "Logo deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete logo: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/review-platforms")
    public ResponseEntity<?> getReviewPlatforms(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Business not found"));

            // Verify ownership
            if (!business.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to access this business"));
            }

            Map<String, String> platforms = Map.of(
                    "googlePlaceId", business.getGooglePlaceId() != null ? business.getGooglePlaceId() : "",
                    "facebookPageUrl", business.getFacebookPageUrl() != null ? business.getFacebookPageUrl() : "",
                    "yelpPageUrl", business.getYelpPageUrl() != null ? business.getYelpPageUrl() : ""
            );

            return ResponseEntity.ok(platforms);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error fetching review platforms: " + e.getMessage()));
        }
    }

    /**
     * Update review platforms configuration
     *
     * UPDATED: Now supports g.page short URL
     */
    @PutMapping("/{businessId}/review-platforms")
    public ResponseEntity<BusinessDto> updateReviewPlatforms(
            @PathVariable Long businessId,
            @RequestBody UpdateBusinessReviewPlatformsDto dto,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // CHANGED: Now passes googleReviewShortUrl as well
            Business updatedBusiness = businessService.updateReviewPlatforms(
                    businessId,
                    dto.getGooglePlaceId(),
                    dto.getGoogleReviewShortUrl(), // NEW PARAMETER
                    dto.getFacebookPageUrl(),
                    dto.getYelpPageUrl(),
                    user
            );

            return ResponseEntity.ok(BusinessMapper.toDto(updatedBusiness));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    /**
     * Manually refresh Google Places data
     *
     *Allows users to re-sync with Google Places API
     */
    @PostMapping("/{businessId}/refresh-google-places")
    public ResponseEntity<BusinessDto> refreshGooglePlaces(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business refreshedBusiness = businessService.refreshGooglePlacesData(businessId, user);

            return ResponseEntity.ok(BusinessMapper.toDto(refreshedBusiness));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    /**
     * Get the current user's business (first business for the user)
     * GET /api/v1/businesses/mine
     */
    @GetMapping("/mine")
    public ResponseEntity<BusinessDto> getMyBusiness(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Get user's first business
            List<Business> businesses = businessRepo.findByUserId(user.getId());

            if (businesses.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // Return first business as DTO
            return ResponseEntity.ok(BusinessMapper.toDto(businesses.get(0)));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}