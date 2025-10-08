package com.reputul.backend.controllers;

import com.reputul.backend.auth.JwtUtil;
import com.reputul.backend.dto.ReviewDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.EmailTemplateRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReputationService;
import com.reputul.backend.util.ReviewMapper;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
@Slf4j
public class ReviewController {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;
    private final ReputationService reputationService;
    private final UserRepository userRepo;
    private final EmailTemplateRepository emailTemplateRepository;
    private final JwtUtil jwtUtil;

    public ReviewController(
            ReviewRepository reviewRepo,
            BusinessRepository businessRepo,
            ReputationService reputationService,
            UserRepository userRepo,
            EmailTemplateRepository emailTemplateRepository,
            JwtUtil jwtUtil
    ) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
        this.reputationService = reputationService;
        this.userRepo = userRepo;
        this.emailTemplateRepository = emailTemplateRepository;
        this.jwtUtil = jwtUtil;
    }

    // ✅ Authenticated internal review submission (with businessId in path)
    @PostMapping("/{businessId}")
    public ResponseEntity<?> createReview(@PathVariable Long businessId,
                                          @RequestBody Review review,
                                          @AuthenticationPrincipal UserDetails userDetails) {
        try {
            // CHANGE: Added tenant scoping check
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepo.findById(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            // CHANGE: Verify the user owns this business
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ User {} attempted to create review for business {} they don't own",
                        user.getEmail(), businessId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to create reviews for this business");
            }

            // Validate rating
            if (review.getRating() < 1 || review.getRating() > 5) {
                return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
            }

            review.setBusiness(business);
            review.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

            Review savedReview = reviewRepo.save(review);
            reputationService.updateBusinessReputationAndBadge(businessId);

            return ResponseEntity.ok(savedReview);
        } catch (Exception e) {
            log.error("Error creating review: ", e);
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
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ User {} attempted to create manual review for business {} they don't own",
                        user.getEmail(), businessId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to add reviews for this business");
            }

            // Validate rating
            if (review.getRating() < 1 || review.getRating() > 5) {
                return ResponseEntity.badRequest().body("Rating must be between 1 and 5.");
            }

            review.setBusiness(business);
            review.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            review.setSource("manual"); // Mark as manually added

            Review savedReview = reviewRepo.save(review);
            reputationService.updateBusinessReputationAndBadge(business.getId());

            return ResponseEntity.ok(savedReview);
        } catch (Exception e) {
            log.error("Error adding manual review: ", e);
            return ResponseEntity.badRequest().body("Error adding manual review: " + e.getMessage());
        }
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<?> getReviewsByBusiness(
            @PathVariable Long businessId,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Get current user for tenant scoping
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Get business and verify it exists
            Business business = businessRepo.findById(businessId)
                    .orElse(null);

            if (business == null) {
                log.warn("⚠️ Business {} not found", businessId);
                return ResponseEntity.notFound().build();
            }

            // CRITICAL - Verify tenant scoping (user owns this business)
            if (!business.getUser().getId().equals(user.getId())) {
                log.warn("⚠️ SECURITY: User {} attempted to access reviews for business {} they don't own",
                        email, businessId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to access reviews for this business");
            }

            // Only fetch reviews if authorization passed
            Sort.Direction direction = sortDir.equalsIgnoreCase("asc") ?
                    Sort.Direction.ASC : Sort.Direction.DESC;
            Sort sort = Sort.by(direction, sortBy);

            List<Review> reviews = reviewRepo.findByBusinessIdOrderBy(businessId, sort);

            List<ReviewDto> reviewDTOs = ReviewMapper.toDTOList(reviews);

            log.info("✅ User {} accessed {} reviews for business {}", email, reviewDTOs.size(), businessId);

            return ResponseEntity.ok(reviewDTOs);

        } catch (RuntimeException e) {
            log.error("Error fetching reviews: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching reviews: " + e.getMessage());
        }
    }

    // Added tenant scoping - only admin or for debugging
    @GetMapping
    public ResponseEntity<?> getAllReviews(@AuthenticationPrincipal UserDetails userDetails) {
        try {
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Only return reviews for businesses owned by this user
            List<Business> userBusinesses = businessRepo.findByUserId(user.getId());
            List<Long> businessIds = userBusinesses.stream()
                    .map(Business::getId)
                    .toList();

            List<Review> reviews = reviewRepo.findAll().stream()
                    .filter(review -> businessIds.contains(review.getBusiness().getId()))
                    .toList();

            log.info("✅ User {} accessed {} total reviews across their businesses", user.getEmail(), reviews.size());
            return ResponseEntity.ok(reviews);

        } catch (RuntimeException e) {
            log.error("Error fetching all reviews: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error fetching reviews: " + e.getMessage());
        }
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
            if (!review.getBusiness().getUser().getId().equals(user.getId())) {
                log.warn("⚠️ User {} attempted to delete review {} for business they don't own",
                        user.getEmail(), reviewId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("You don't have permission to delete this review");
            }

            Long businessId = review.getBusiness().getId();
            reviewRepo.delete(review);

            // Update reputation after deletion
            reputationService.updateBusinessReputationAndBadge(businessId);

            log.info("✅ User {} deleted review {} for business {}", user.getEmail(), reviewId, businessId);
            return ResponseEntity.ok("Review deleted successfully");
        } catch (Exception e) {
            log.error("Error deleting review: ", e);
            return ResponseEntity.badRequest().body("Error deleting review: " + e.getMessage());
        }
    }

    @PostMapping("/templates")
    @Transactional
    public ResponseEntity<String> fixTemplatesFinal(@AuthenticationPrincipal User user) {
        try {
            // Step 1: Mark ALL existing templates as non-default and inactive
            List<EmailTemplate> existingTemplates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
            for (EmailTemplate template : existingTemplates) {
                template.setIsDefault(false);
                template.setIsActive(false);
                // Add prefix to avoid name conflicts
                if (!template.getName().startsWith("[OLD]")) {
                    template.setName("[OLD] " + template.getName());
                }
            }
            emailTemplateRepository.saveAll(existingTemplates);

            // Step 2: Create brand new HTML templates with unique names
            createNewHtmlTemplates(user);

            return ResponseEntity.ok("SUCCESS: Created new HTML templates! Old templates preserved for review history.");

        } catch (Exception e) {
            log.error("Error fixing templates: ", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    private void createNewHtmlTemplates(User user) {
        // Create new template with unique name
        EmailTemplate newHtmlTemplate = EmailTemplate.builder()
                .name("HTML Review Request Email v2") // Different name to avoid conflicts
                .subject("How was your {{serviceType}} experience, {{customerName}}?")
                .body("""
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Share Your Experience</title>
                </head>
                <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #f4f4f4;">
                    <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                        
                        <!-- Header -->
                        <div style="background-color: #2563eb; color: white; padding: 30px 20px; text-align: center;">
                            <h1 style="margin: 0; font-size: 24px;">{{businessName}}</h1>
                            <p style="margin: 5px 0 0 0; opacity: 0.9;">We value your feedback</p>
                        </div>
                        
                        <!-- Main Content -->
                        <div style="padding: 30px 20px;">
                            <p style="margin: 0 0 15px 0; font-size: 16px;">Hi {{customerName}},</p>
                            <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you for choosing {{businessName}} for your {{serviceType}} on {{serviceDate}}.</p>
                            <p style="margin: 0 0 25px 0; font-size: 16px;">We hope you were completely satisfied with our service. Your honest feedback helps us improve and assists other customers in making informed decisions.</p>
                            
                            <!-- Review Buttons Section -->
                            <div style="background-color: #f8fafc; padding: 25px; border-radius: 8px; text-align: center; margin: 25px 0;">
                                <h2 style="margin: 0 0 20px 0; color: #374151; font-size: 20px;">Share Your Experience</h2>
                                <p style="margin: 0 0 20px 0; color: #6b7280; font-size: 14px;">Choose your preferred platform:</p>
                                
                                <!-- Google Review Button -->
                                <div style="margin-bottom: 15px;">
                                    <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #16a34a; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                        🌟 Leave Google Review
                                    </a>
                                </div>
                                
                                <!-- Facebook Review Button -->
                                <div style="margin-bottom: 15px;">
                                    <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #1877f2; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                        📘 Facebook Review
                                    </a>
                                </div>
                                
                                <!-- Private Feedback Button -->
                                <div style="margin-bottom: 15px;">
                                    <a href="{{privateReviewUrl}}" style="display: inline-block; background-color: #6b7280; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                        💬 Private Feedback
                                    </a>
                                </div>
                            </div>
                        </div>
                        
                        <!-- Footer -->
                        <div style="background-color: #f9fafb; padding: 20px; border-top: 1px solid #e5e7eb; text-align: center;">
                            <p style="margin: 0 0 5px 0; font-weight: bold; color: #374151;">{{businessName}}</p>
                            <p style="margin: 0 0 5px 0; font-size: 14px; color: #6b7280;">{{businessPhone}}</p>
                            <p style="margin: 0 0 15px 0; font-size: 14px; color: #6b7280;">{{businessWebsite}}</p>
                            <p style="margin: 0; font-size: 12px; color: #9ca3af;">
                                <a href="{{unsubscribeUrl}}" style="color: #6b7280; text-decoration: none;">Unsubscribe</a>
                            </p>
                        </div>
                    </div>
                </body>
                </html>
                """)
                .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                .isActive(true)
                .isDefault(true) // This will be the new default
                .availableVariables("{{customerName}},{{businessName}},{{serviceType}},{{serviceDate}},{{businessPhone}},{{businessWebsite}},{{googleReviewUrl}},{{facebookReviewUrl}},{{privateReviewUrl}},{{unsubscribeUrl}}")
                .user(user)
                .build();

        emailTemplateRepository.save(newHtmlTemplate);
        log.info("Created new HTML template for user {}", user.getId());
    }
}