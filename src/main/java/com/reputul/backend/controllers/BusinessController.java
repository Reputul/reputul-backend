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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/businesses")
@Slf4j  // CHANGE: Added logging
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

            log.info("✅ User {} accessed {} businesses", email, dtos.size());
            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Error fetching businesses: ", e);
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

            log.info("✅ User {} created business {} (ID: {})", email, business.getName(), savedBusiness.getId());

            // Return DTO instead of entity
            return ResponseEntity.ok(BusinessMapper.toDto(savedBusiness));
        } catch (Exception e) {
            log.error("Error creating business: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error creating business: " + e.getMessage()));
        }
    }

    // CHANGE: This endpoint should probably also check authorization
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<BusinessDto>> getByUser(
            @PathVariable Long userId,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            String email = userDetails.getUsername();
            User currentUser = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // CHANGE: Only allow users to fetch their own businesses
            if (!currentUser.getId().equals(userId)) {
                log.warn("⚠️ User {} attempted to access businesses for user {}", email, userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            List<Business> businesses = businessRepo.findByUserId(userId);

            List<BusinessDto> dtos = businesses.stream()
                    .map(BusinessMapper::toDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(dtos);
        } catch (Exception e) {
            log.error("Error fetching businesses by user: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // FIXED: Added authorization check
    @GetMapping("/{id}")
    public ResponseEntity<?> getBusinessById(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            // CHANGE: Get current user for authorization
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(id)
                    .orElse(null);

            if (business == null) {
                log.warn("⚠️ Business {} not found", id);
                return ResponseEntity.notFound().build();
            }

            // CHANGE: CRITICAL - Verify tenant scoping (user owns this business)
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ SECURITY: User {} attempted to access business {} they don't own",
                        email, id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to access this business"));
            }

            BusinessResponseDto response = BusinessResponseDto.builder()
                    .id(business.getId())
                    .name(business.getName())
                    .industry(business.getIndustry())
                    .phone(business.getPhone())
                    .website(business.getWebsite())
                    .address(business.getAddress())
                    .reputationScore(business.getReputationScore())
                    .badge(business.getBadge())
                    .reviewCount((int) reviewRepo.countByBusinessId(business.getId()))
                    .reviewPlatformsConfigured(business.getReviewPlatformsConfigured())
                    .build();

            log.info("✅ User {} accessed business {}", email, id);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching business by id: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error fetching business: " + e.getMessage()));
        }
    }

    // FIXED: Added authorization check
    @GetMapping("/{id}/review-summary")
    public ResponseEntity<?> getReviewSummary(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {

        try {
            // CHANGE: Get current user for authorization
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(id)
                    .orElse(null);

            if (business == null) {
                log.warn("⚠️ Business {} not found", id);
                return ResponseEntity.notFound().build();
            }

            // CHANGE: CRITICAL - Verify tenant scoping (user owns this business)
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ SECURITY: User {} attempted to access review summary for business {} they don't own",
                        email, id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to access this business"));
            }

            double avgRating = reviewRepo.findAverageRatingByBusinessId(id) != null
                    ? reviewRepo.findAverageRatingByBusinessId(id) : 0.0;
            long total = reviewRepo.countByBusinessId(id);
            List<Review> recent = reviewRepo.findTop1ByBusinessIdOrderByCreatedAtDesc(id);
            String comment = recent.isEmpty() ? "No reviews yet." : recent.get(0).getComment();

            String badge = business.getBadge() != null ? business.getBadge() : "Unranked";

            ReviewSummaryDto summary = new ReviewSummaryDto(avgRating, (int) total, comment, badge);

            log.info("✅ User {} accessed review summary for business {}", email, id);
            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Error fetching review summary: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error fetching review summary: " + e.getMessage()));
        }
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
                log.warn("⚠️ User {} attempted to update business {} they don't own", email, id);
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

            log.info("✅ User {} updated business {}", email, id);

            // Return DTO
            return ResponseEntity.ok(BusinessMapper.toDto(biz));
        } catch (Exception e) {
            log.error("Error updating business: ", e);
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
                log.warn("⚠️ User {} attempted to delete business {} they don't own", email, id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to delete this business"));
            }

            businessRepo.delete(business);

            log.info("✅ User {} deleted business {}", email, id);
            return ResponseEntity.ok(Map.of("message", "Business deleted successfully"));
        } catch (Exception e) {
            log.error("Error deleting business: ", e);
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

            log.info("✅ Logo uploaded for business {}", id);

            // Return DTO
            return ResponseEntity.ok(BusinessMapper.toDto(business));

        } catch (IllegalArgumentException e) {
            log.error("Invalid logo upload: ", e);
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error uploading logo: ", e);
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

            log.info("✅ Logo deleted for business {}", id);
            return ResponseEntity.ok(Map.of("message", "Logo deleted successfully"));
        } catch (Exception e) {
            log.error("Error deleting logo: ", e);
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
                log.warn("⚠️ User {} attempted to access review platforms for business {} they don't own",
                        email, id);
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
            log.error("Error fetching review platforms: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error fetching review platforms: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}/review-platforms")
    public ResponseEntity<?> updateReviewPlatforms(
            @PathVariable Long id,
            @RequestBody Map<String, String> platformData,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(id)
                    .orElseThrow(() -> new RuntimeException("Business not found"));

            // Verify ownership
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ User {} attempted to update review platforms for business {} they don't own",
                        email, id);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "You don't have permission to update this business"));
            }

            // Update platform URLs
            business.setGooglePlaceId(platformData.get("googlePlaceId"));
            business.setFacebookPageUrl(platformData.get("facebookPageUrl"));
            business.setYelpPageUrl(platformData.get("yelpPageUrl"));

            // Mark as configured if at least one platform is set
            boolean hasAtLeastOnePlatform =
                    (platformData.get("googlePlaceId") != null && !platformData.get("googlePlaceId").isEmpty()) ||
                            (platformData.get("facebookPageUrl") != null && !platformData.get("facebookPageUrl").isEmpty()) ||
                            (platformData.get("yelpPageUrl") != null && !platformData.get("yelpPageUrl").isEmpty());

            business.setReviewPlatformsConfigured(hasAtLeastOnePlatform);

            businessRepo.save(business);

            log.info("✅ User {} updated review platforms for business {}", email, id);

            return ResponseEntity.ok(Map.of(
                    "message", "Review platforms updated successfully",
                    "reviewPlatformsConfigured", hasAtLeastOnePlatform
            ));
        } catch (Exception e) {
            log.error("Error updating review platforms: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error updating review platforms: " + e.getMessage()));
        }
    }
}