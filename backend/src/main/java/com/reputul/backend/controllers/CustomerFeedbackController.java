package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;

@RestController
@RequestMapping("/api/customers")
@CrossOrigin(origins = "*") // Allow all origins for public feedback
public class CustomerFeedbackController {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;

    public CustomerFeedbackController(
            CustomerRepository customerRepository,
            BusinessRepository businessRepository,
            ReviewRepository reviewRepository) {
        this.customerRepository = customerRepository;
        this.businessRepository = businessRepository;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Get customer and business information for feedback page
     * Public endpoint - no authentication required
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
     * Submit customer feedback
     * Public endpoint - no authentication required
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

            // Create review record
            Review review = Review.builder()
                    .rating(request.getRating())
                    .comment(request.getComment().trim())
                    .business(business)
                    .customer(customer) // Link to customer for tracking
                    .customerName(customer.getName())
                    .customerEmail(customer.getEmail())
                    .source(request.getType() != null && request.getType().equals("private") ? "private_feedback" : "public_feedback")
                    .createdAt(LocalDateTime.now())
                    .build();

            Review savedReview = reviewRepository.save(review);

            // Update customer record to track feedback submission
            customer.setLastFeedbackDate(LocalDateTime.now());
            customer.setFeedbackSubmitted(true);
            // Handle null feedback count safely
            Integer currentCount = customer.getFeedbackCount();
            customer.setFeedbackCount(currentCount != null ? currentCount + 1 : 1);
            customerRepository.save(customer);

            FeedbackResponse response = FeedbackResponse.builder()
                    .id(savedReview.getId())
                    .success(true)
                    .message("Thank you for your feedback! We truly appreciate your time and insights.")
                    .build();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error submitting feedback: " + e.getMessage());
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/feedback/health")
    public ResponseEntity<String> healthCheck() {
        return ResponseEntity.ok("Customer feedback service is running");
    }
}