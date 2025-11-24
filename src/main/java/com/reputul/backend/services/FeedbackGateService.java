package com.reputul.backend.services;

import com.reputul.backend.dto.FeedbackGateRequest;
import com.reputul.backend.dto.FeedbackGateResponse;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.util.ReviewLinks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeedbackGateService {

    private final CustomerRepository customerRepository;
    private final ReviewRepository reviewRepository;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * GOOGLE-COMPLIANT: Process rating gate and show ALL options to ALL customers
     * We collect the rating for internal use, but don't filter platform access
     */
    @Transactional
    public FeedbackGateResponse processRatingGate(Long customerId, FeedbackGateRequest request) {
        log.info("Processing rating gate for customer {} with rating {}", customerId, request.getRating());

        // Validate customer exists
        Optional<Customer> customerOpt = customerRepository.findById(customerId);
        if (customerOpt.isEmpty()) {
            throw new RuntimeException("Customer not found or link has expired");
        }

        Customer customer = customerOpt.get();
        Business business = customer.getBusiness();

        if (business == null) {
            throw new RuntimeException("Associated business not found");
        }

        // Store the initial rating as a review record for internal tracking ONLY
        Review gateReview = Review.builder()
                .rating(request.getRating())
                .comment(request.getInitialComment() != null ?
                        request.getInitialComment() : "Initial rating from feedback gate")
                .business(business)
                .customer(customer)
                .customerName(customer.getName())
                .customerEmail(customer.getEmail())
                .source("internal_feedback_gate") // Mark as internal only
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        reviewRepository.save(gateReview);

        // Update customer feedback tracking
        customer.setLastFeedbackDate(OffsetDateTime.now(ZoneOffset.UTC));
        Integer currentCount = customer.getFeedbackCount();
        customer.setFeedbackCount(currentCount != null ? currentCount + 1 : 1);
        customerRepository.save(customer);

        // COMPLIANT: Build response that shows ALL options to ALL customers
        return buildCompliantGateResponse(customer, business, request.getRating());
    }

    /**
     * GOOGLE-COMPLIANT: Always show ALL review platforms to ALL customers
     * The rating is used for internal prioritization only, not filtering access
     */
    private FeedbackGateResponse buildCompliantGateResponse(Customer customer, Business business, Integer rating) {

        // Generate ALL platform URLs for ALL customers
        String googleReviewUrl = ReviewLinks.googleReviewUrl(business);
        String facebookReviewUrl = ReviewLinks.facebookReviewUrl(business);
        String yelpReviewUrl = ReviewLinks.yelpUrl(business);
        String privateFeedbackUrl = frontendUrl + "/feedback/" + customer.getId();

        // Customize messaging based on rating, but show ALL options
        String message;
        String thankYouMessage;
        String internalPriority;

        if (rating >= 4) {
            // Happy customers - encourage public reviews but still show all options
            message = "Thank you for the great rating! We'd love for you to share your experience.";
            thankYouMessage = "Your " + rating + "-star rating means a lot to us! Please choose how you'd like to share your feedback:";
            internalPriority = "PUBLIC_ENCOURAGED";
        } else {
            // Less happy customers - encourage detailed feedback but still show all options
            message = "Thank you for your honest feedback. We'd love to hear more details.";
            thankYouMessage = "We appreciate your " + rating + "-star rating. Please choose how you'd like to share more feedback:";
            internalPriority = "DETAILED_FEEDBACK_ENCOURAGED";
        }

        return FeedbackGateResponse.builder()
                .customerId(customer.getId())
                .customerName(customer.getName())
                .businessName(business.getName())
                .submittedRating(rating)
                .success(true)
                // CRITICAL: Always provide ALL options to ALL customers
                .googleReviewUrl(googleReviewUrl)
                .facebookReviewUrl(facebookReviewUrl)
                .yelpReviewUrl(yelpReviewUrl)
                .privateFeedbackUrl(privateFeedbackUrl)
                // Use messaging to encourage appropriate paths, not restrict access
                .message(message)
                .thankYouMessage(thankYouMessage)
                // Internal use only - not exposed to frontend
                .routingDecision(internalPriority)
                .build();
    }

    /**
     * Get customer info for feedback gate page
     */
    public FeedbackGateResponse getCustomerGateInfo(Long customerId) {
        Optional<Customer> customerOpt = customerRepository.findById(customerId);
        if (customerOpt.isEmpty()) {
            throw new RuntimeException("Customer not found or link has expired");
        }

        Customer customer = customerOpt.get();
        Business business = customer.getBusiness();

        if (business == null) {
            throw new RuntimeException("Associated business not found");
        }

        // Return basic info for the gate page (before rating submission)
        return FeedbackGateResponse.builder()
                .customerId(customer.getId())
                .customerName(customer.getName())
                .businessName(business.getName())
                .success(true)
                .message("Please rate your experience with " + business.getName())
                .build();
    }

    /**
     * Helper method to determine if customer has already used the gate
     */
    public boolean hasCustomerUsedGate(Long customerId) {
        Optional<Customer> customerOpt = customerRepository.findById(customerId);
        if (customerOpt.isEmpty()) {
            return false;
        }

        // Check if there's already an internal feedback gate review for this customer
        return reviewRepository.existsByCustomerIdAndSource(customerId, "internal_feedback_gate");
    }

}