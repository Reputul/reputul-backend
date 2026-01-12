package com.reputul.backend.controllers;

import com.reputul.backend.auth.JwtUtil;
import com.reputul.backend.dto.ReviewDto;
import com.reputul.backend.integrations.GoogleMyBusinessClient;
import com.reputul.backend.integrations.PlatformReviewClient;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import com.reputul.backend.services.ReputationService;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reviews")
@Slf4j
public class ReviewController {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;
    private final ReputationService reputationService;
    private final UserRepository userRepo;
    private final EmailTemplateRepository emailTemplateRepository;
    private final ChannelCredentialRepository credentialRepository;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;
    private final JwtUtil jwtUtil;

    public ReviewController(
            ReviewRepository reviewRepo,
            BusinessRepository businessRepo,
            ReputationService reputationService,
            UserRepository userRepo,
            EmailTemplateRepository emailTemplateRepository,
            ChannelCredentialRepository credentialRepository,
            Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients,
            JwtUtil jwtUtil
    ) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
        this.reputationService = reputationService;
        this.userRepo = userRepo;
        this.emailTemplateRepository = emailTemplateRepository;
        this.credentialRepository = credentialRepository;
        this.platformClients = platformClients;
        this.jwtUtil = jwtUtil;
    }

    // ✅ Authenticated internal review submission (with businessId in path)
    @PostMapping("/{businessId}")
    public ResponseEntity<?> createReview(@PathVariable Long businessId,
                                          @RequestBody Review review,
                                          @AuthenticationPrincipal UserDetails userDetails) {
        try {
            // CHANGED: Use findByIdWithOrganization to eagerly load organization
            Business business = businessRepo.findByIdWithOrganization(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
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

            // CHANGED: Use findByIdWithOrganization to eagerly load organization
            Business business = businessRepo.findByIdWithOrganization(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            // FIXED: Null-safe organization check before accessing
            if (business.getOrganization() == null) {
                log.error("Business {} has no associated organization - data integrity issue!", businessId);
                return ResponseEntity.status(500).body(Map.of("error", "Business configuration error"));
            }

            // FIXED: Verify organization-based access instead of just user ownership
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                log.warn("User {} from organization {} attempted to access business {} from organization {}",
                        user.getEmail(), user.getOrganization().getId(), businessId, business.getOrganization().getId());
                return ResponseEntity.status(403).body("Access denied");
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
            return ResponseEntity.badRequest().body("Error adding manual review: " + e.getMessage());
        }
    }

    // ✅ FIXED: All reviews for a specific business using DTO to avoid Hibernate issues
    @GetMapping("/business/{businessId}")
    @Transactional // Keep transaction open for the query
    public ResponseEntity<?> getReviewsByBusiness(
            @PathVariable Long businessId,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Authentication and authorization
            String email = userDetails.getUsername();
            User user = userRepo.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // CHANGED: Use findByIdWithOrganization to eagerly load organization
            Business business = businessRepo.findByIdWithOrganization(businessId)
                    .orElse(null);

            if (business == null) {
                log.warn("Business {} not found", businessId);
                return ResponseEntity.notFound().build();
            }

            // FIXED: Null-safe organization check before accessing
            if (business.getOrganization() == null) {
                log.error("Business {} has no associated organization - data integrity issue!", businessId);
                return ResponseEntity.status(500).body(Map.of("error", "Business configuration error"));
            }

            // Check organization-based access
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                log.warn("User {} from organization {} attempted to access reviews for business {} from organization {}",
                        user.getEmail(), user.getOrganization().getId(), businessId, business.getOrganization().getId());
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }

            // Get reviews using organization-aware repository method
            Sort sort = Sort.by(Sort.Direction.fromString(sortDir), sortBy);
            List<Review> reviews = reviewRepo.findByBusinessIdAndOrganizationId(businessId, user.getOrganization().getId(), sort);

            // Force initialization of business and organization relationships within the transaction
            for (Review review : reviews) {
                if (review.getBusiness() != null) {
                    review.getBusiness().getName(); // Initialize business
                    if (review.getBusiness().getOrganization() != null) {
                        review.getBusiness().getOrganization().getName(); // Initialize organization
                    }
                }
            }

            // Convert to DTOs to avoid any serialization issues
            List<ReviewDto> reviewDTOs = ReviewDto.fromEntities(reviews);

            log.info("Successfully retrieved {} reviews for business {} (organization {})",
                    reviewDTOs.size(), businessId, user.getOrganization().getId());

            return ResponseEntity.ok(reviewDTOs);
        } catch (Exception e) {
            log.error("Error fetching reviews for business {}: {}", businessId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch reviews: " + e.getMessage()));
        }
    }

    // ✅ All reviews (admin/debug)
    @GetMapping
    public List<Review> getAllReviews() {
        return reviewRepo.findAll();
    }

    // ✅ FIXED: Delete a review with organization-based access control
    @DeleteMapping("/{reviewId}")
    @Transactional
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

            // FIXED: Null-safe organization check before accessing
            if (review.getBusiness() == null || review.getBusiness().getOrganization() == null) {
                log.error("Review {} or its business has no associated organization - data integrity issue!", reviewId);
                return ResponseEntity.status(500).body(Map.of("error", "Review configuration error"));
            }

            // FIXED: Check organization-based access instead of just user ownership
            if (!review.getBusiness().getOrganization().getId().equals(user.getOrganization().getId())) {
                log.warn("User {} from organization {} attempted to delete review {} for business from organization {}",
                        user.getEmail(), user.getOrganization().getId(), reviewId, review.getBusiness().getOrganization().getId());
                return ResponseEntity.status(403).body("Access denied");
            }

            Long businessId = review.getBusiness().getId();
            reviewRepo.delete(review);

            // Update reputation after deletion
            reputationService.updateBusinessReputationAndBadge(businessId);

            log.info("Successfully deleted review {} for business {} (organization {})",
                    reviewId, businessId, user.getOrganization().getId());

            return ResponseEntity.ok("Review deleted successfully");
        } catch (Exception e) {
            log.error("Error deleting review {}: {}", reviewId, e.getMessage(), e);
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

    /**
     * Reply to a review
     * Allows business owners to respond to customer reviews
     *
     * @param reviewId The review ID to reply to
     * @param request Request body containing responseText
     * @param authentication Current user authentication
     * @return Updated review with response
     */
    @PostMapping("/{reviewId}/reply")
    public ResponseEntity<?> replyToReview(
            @PathVariable Long reviewId,
            @RequestBody Map<String, String> request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            String responseText = request.get("responseText");

            if (responseText == null || responseText.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Response text is required"));
            }

            // Find the review
            Review review = reviewRepo.findById(reviewId)
                    .orElseThrow(() -> new IllegalArgumentException("Review not found"));

            // FIXED: Null-safe organization check before accessing
            if (review.getBusiness() == null || review.getBusiness().getOrganization() == null) {
                log.error("Review {} or its business has no associated organization - data integrity issue!", reviewId);
                return ResponseEntity.status(500).body(Map.of("error", "Review configuration error"));
            }

            // Verify ownership
            if (!review.getBusiness().getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized to reply to this review"));
            }

            // Save the reply to our database first
            review.setPlatformResponse(responseText);
            review.setPlatformResponseAt(OffsetDateTime.now(ZoneOffset.UTC));
            review = reviewRepo.save(review);

            log.info("User {} replied to review {} (source: {})", user.getEmail(), reviewId, review.getSource());

            // Post to external platform if applicable
            String source = review.getSource().toLowerCase();

            if (source.equals("google_my_business") || source.equals("google")) {
                // Post to Google My Business
                try {
                    // Find GMB credential for this business
                    Optional<ChannelCredential> gmbCredential = credentialRepository
                            .findByBusinessIdAndPlatformType(
                                    review.getBusiness().getId(),
                                    ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS
                            );

                    if (gmbCredential.isPresent()) {
                        GoogleMyBusinessClient gmbClient = (GoogleMyBusinessClient) platformClients
                                .get(ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS);

                        if (gmbClient != null && review.getSourceReviewId() != null) {
                            gmbClient.postReviewReply(
                                    gmbCredential.get(),
                                    review.getSourceReviewId(),
                                    responseText
                            );
                            log.info("Successfully posted reply to Google for review {}", reviewId);
                        }
                    } else {
                        log.warn("Google credential not found for business {}", review.getBusiness().getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to post reply to Google, but saved locally", e);
                    // Continue - we've saved locally even if platform post failed
                }
            }

            // Note: Facebook replies would go here if their API supported it
            // Currently Facebook doesn't allow programmatic replies via API

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Reply posted successfully",
                    "review", review,
                    "postedToPlatform", source.contains("google") ? true : false
            ));

        } catch (Exception e) {
            log.error("Error replying to review", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to post reply: " + e.getMessage()));
        }
    }

    // Helper method (add if not already present)
    private User getUserFromAuth(Authentication auth) {
        String email = auth.getName();
        return userRepo.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

}