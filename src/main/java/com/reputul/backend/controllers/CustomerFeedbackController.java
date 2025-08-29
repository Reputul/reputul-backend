package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.services.FeedbackGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
@Slf4j
public class CustomerFeedbackController {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;
    private final FeedbackGateService feedbackGateService;

    // ===========================================
    // NEW: FEEDBACK GATE ENDPOINTS
    // ===========================================

    /**
     * Get customer info for feedback gate page
     * NEW ENDPOINT: /api/customers/{customerId}/gate-info
     */
    @GetMapping("/{customerId}/gate-info")
    public ResponseEntity<?> getCustomerGateInfo(@PathVariable Long customerId) {
        try {
            FeedbackGateResponse response = feedbackGateService.getCustomerGateInfo(customerId);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.error("Error fetching customer gate info for ID {}: {}", customerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error fetching customer gate info: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error retrieving customer information"));
        }
    }

    /**
     * Process rating gate submission - CORE NEW FUNCTIONALITY
     * NEW ENDPOINT: /api/customers/{customerId}/rate
     */
    @PostMapping("/{customerId}/rate")
    public ResponseEntity<?> submitRatingGate(
            @PathVariable Long customerId,
            @Valid @RequestBody FeedbackGateRequest request) {

        try {
            log.info("Rating gate submission for customer {} with rating {}", customerId, request.getRating());

            FeedbackGateResponse response = feedbackGateService.processRatingGate(customerId, request);

            log.info("Rating gate processed successfully - routing decision: {} for customer {}",
                    response.getRoutingDecision(), customerId);

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            log.error("Error processing rating gate for customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error processing rating gate: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error processing rating"));
        }
    }

    /**
     * Check if customer has already used the feedback gate
     * NEW ENDPOINT: /api/customers/{customerId}/gate-status
     */
    @GetMapping("/{customerId}/gate-status")
    public ResponseEntity<?> checkGateStatus(@PathVariable Long customerId) {
        try {
            boolean hasUsedGate = feedbackGateService.hasCustomerUsedGate(customerId);
            return ResponseEntity.ok(Map.of(
                    "customerId", customerId,
                    "hasUsedGate", hasUsedGate,
                    "message", hasUsedGate ? "Customer has already provided initial rating" : "Customer can use feedback gate"
            ));
        } catch (Exception e) {
            log.error("Error checking gate status for customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error checking gate status"));
        }
    }

    // ===========================================
    // EXISTING ENDPOINTS (PRESERVED)
    // ===========================================

    /**
     * Get customer and business information for feedback page
     * EXISTING ENDPOINT - Used by private feedback form
     */
    @GetMapping("/{customerId}/feedback-info")
    public ResponseEntity<?> getCustomerFeedbackInfo(@PathVariable Long customerId) {
        try {
            Optional<Customer> customerOpt = customerRepository.findById(customerId);

            if (customerOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Customer not found or link has expired.");
            }

            Customer customer = customerOpt.get();
            Business business = customer.getBusiness();

            if (business == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Associated business not found.");
            }

            CustomerFeedbackInfoDto.CustomerInfoDto customerDto =
                    CustomerFeedbackInfoDto.CustomerInfoDto.builder()
                            .id(customer.getId())
                            .name(customer.getName())
                            .email(customer.getEmail())
                            .phone(customer.getPhone())
                            .serviceType(customer.getServiceType())
                            .serviceDate(customer.getServiceDate())
                            .status(customer.getStatus())
                            .tags(customer.getTags())
                            .build();

            CustomerFeedbackInfoDto.BusinessInfoDto businessDto =
                    CustomerFeedbackInfoDto.BusinessInfoDto.builder()
                            .id(business.getId())
                            .name(business.getName())
                            .industry(business.getIndustry())
                            .phone(business.getPhone())
                            .website(business.getWebsite())
                            .address(business.getAddress())
                            .googlePlaceId(business.getGooglePlaceId())
                            .facebookPageUrl(business.getFacebookPageUrl())
                            .yelpPageUrl(business.getYelpPageUrl())
                            .reviewPlatformsConfigured(business.getReviewPlatformsConfigured())
                            .build();

            CustomerFeedbackInfoDto response = CustomerFeedbackInfoDto.builder()
                    .customer(customerDto)
                    .business(businessDto)
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error retrieving customer information: " + e.getMessage());
        }
    }

    /**
     * Submit customer feedback - PRIVATE FEEDBACK FORM
     * EXISTING ENDPOINT - Now primarily used for private feedback (1-3 star followups)
     */
    @PostMapping("/{customerId}/feedback")
    public ResponseEntity<?> submitCustomerFeedback(
            @PathVariable Long customerId,
            @RequestBody SubmitFeedbackRequest request) {
        try {
            // Validate request
            if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
                return ResponseEntity.badRequest()
                        .body("Rating must be between 1 and 5");
            }

            if (request.getComment() == null || request.getComment().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body("Comment is required");
            }

            // Find customer
            Optional<Customer> customerOpt = customerRepository.findById(customerId);
            if (customerOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Customer not found or link has expired.");
            }

            Customer customer = customerOpt.get();
            Business business = customer.getBusiness();

            if (business == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Associated business not found.");
            }

            // Create review record - Now marked more specifically
            Review review = Review.builder()
                    .rating(request.getRating())
                    .comment(request.getComment().trim())
                    .business(business)
                    .customer(customer)
                    .customerName(customer.getName())
                    .customerEmail(customer.getEmail())
                    .source(request.getType() != null && request.getType().equals("private") ?
                            "private_feedback" : "detailed_feedback") // More specific labeling
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            Review savedReview = reviewRepository.save(review);

            // Update customer record
            customer.setLastFeedbackDate(OffsetDateTime.now(ZoneOffset.UTC));
            customer.setFeedbackSubmitted(true);
            Integer currentCount = customer.getFeedbackCount();
            customer.setFeedbackCount(currentCount != null ? currentCount + 1 : 1);
            customerRepository.save(customer);

            FeedbackResponse response = FeedbackResponse.builder()
                    .id(savedReview.getId())
                    .success(true)
                    .message("Thank you for your detailed feedback! We truly appreciate your time and insights.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error submitting feedback for customer {}: {}", customerId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error submitting feedback: " + e.getMessage());
        }
    }

    // ===========================================
    // UTILITY ENDPOINTS (PRESERVED)
    // ===========================================

    /**
     * Test endpoint to verify customer exists and data is accessible
     * EXISTING ENDPOINT
     */
    @GetMapping("/{customerId}/test")
    public ResponseEntity<?> testCustomerAccess(@PathVariable Long customerId) {
        try {
            Optional<Customer> customerOpt = customerRepository.findById(customerId);

            if (customerOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "found", false,
                                "customerId", customerId,
                                "message", "Customer not found"
                        ));
            }

            Customer customer = customerOpt.get();
            Business business = customer.getBusiness();

            return ResponseEntity.ok(Map.of(
                    "found", true,
                    "customerId", customer.getId(),
                    "customerName", customer.getName(),
                    "businessName", business != null ? business.getName() : "No business",
                    "serviceType", customer.getServiceType(),
                    "email", customer.getEmail(),
                    "newFeedbackGateUrl", "http://localhost:3000/feedback-gate/" + customer.getId(),
                    "existingFeedbackUrl", "http://localhost:3000/feedback/" + customer.getId(),
                    "gateEndpoint", "http://localhost:8080/api/customers/" + customer.getId() + "/gate-info",
                    "rateEndpoint", "http://localhost:8080/api/customers/" + customer.getId() + "/rate"
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "found", false,
                            "error", e.getMessage(),
                            "customerId", customerId
                    ));
        }
    }

    /**
     * Health check endpoint
     * EXISTING ENDPOINT
     */
    @GetMapping("/feedback/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Customer feedback service is running with feedback gate support");
    }
}