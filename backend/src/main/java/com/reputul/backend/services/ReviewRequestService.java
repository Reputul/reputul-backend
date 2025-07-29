package com.reputul.backend.services;

import com.reputul.backend.dto.ReviewRequestDto;
import com.reputul.backend.dto.SendReviewRequestDto;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewRequestService {

    private final ReviewRequestRepository reviewRequestRepository;
    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    @Transactional
    public ReviewRequestDto sendReviewRequest(User user, SendReviewRequestDto request) {
        log.info("Sending review request for customer {} using template {}", request.getCustomerId(), request.getTemplateId());

        // Validate customer belongs to user's business
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (!customer.getBusiness().getOwner().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied: Customer does not belong to your business");
        }

        // Get template and business
        EmailTemplate template = emailTemplateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Business business = customer.getBusiness();

        // Generate review link
        String reviewLink = emailService.generateReviewLink(business);

        // Process template with customer data
        String processedSubject = emailTemplateService.processTemplate(template.getSubject(), customer, business, reviewLink);
        String processedBody = emailTemplateService.processTemplate(template.getBody(), customer, business, reviewLink);

        // Create review request record
        ReviewRequest reviewRequest = ReviewRequest.builder()
                .customer(customer)
                .business(business)
                .emailTemplate(template)
                .recipientEmail(customer.getEmail())
                .subject(processedSubject)
                .emailBody(processedBody)
                .reviewLink(reviewLink)
                .status(ReviewRequest.RequestStatus.PENDING)
                .build();

        reviewRequest = reviewRequestRepository.save(reviewRequest);

        // Send email
        boolean emailSent = emailService.sendReviewRequest(
                customer,
                business,
                processedSubject,
                processedBody,
                reviewLink
        );

        // Update status based on send result
        if (emailSent) {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
            reviewRequest.setSentAt(LocalDateTime.now());
            log.info("Review request sent successfully to {}", customer.getEmail());
        } else {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.FAILED);
            reviewRequest.setErrorMessage("Failed to send email via SendGrid");
            log.error("Failed to send review request to {}", customer.getEmail());
        }

        reviewRequest = reviewRequestRepository.save(reviewRequest);

        return convertToDto(reviewRequest);
    }

    public List<ReviewRequestDto> getReviewRequestsByBusiness(User user, Long businessId) {
        // Verify business belongs to user
        Business business = businessRepository.findByIdAndOwnerId(businessId, user.getId())
                .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

        List<ReviewRequest> requests = reviewRequestRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);
        return requests.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    public List<ReviewRequestDto> getAllReviewRequestsByUser(User user) {
        List<ReviewRequest> requests = reviewRequestRepository.findByOwnerId(user.getId());
        return requests.stream().map(this::convertToDto).collect(Collectors.toList());
    }

    public ReviewRequestDto updateStatus(Long requestId, ReviewRequest.RequestStatus status) {
        ReviewRequest request = reviewRequestRepository.findById(requestId)
                .orElseThrow(() -> new RuntimeException("Review request not found"));

        request.setStatus(status);

        // Set timestamp based on status
        switch (status) {
            case OPENED -> request.setOpenedAt(LocalDateTime.now());
            case CLICKED -> request.setClickedAt(LocalDateTime.now());
            case COMPLETED -> request.setReviewedAt(LocalDateTime.now());
        }

        request = reviewRequestRepository.save(request);
        return convertToDto(request);
    }

    private ReviewRequestDto convertToDto(ReviewRequest request) {
        return ReviewRequestDto.builder()
                .id(request.getId())
                .customerId(request.getCustomer().getId())
                .customerName(request.getCustomer().getName())
                .customerEmail(request.getCustomer().getEmail())
                .businessId(request.getBusiness().getId())
                .businessName(request.getBusiness().getName())
                .templateId(request.getEmailTemplate().getId())
                .templateName(request.getEmailTemplate().getName())
                .recipientEmail(request.getRecipientEmail())
                .subject(request.getSubject())
                .reviewLink(request.getReviewLink())
                .status(request.getStatus())
                .sentAt(request.getSentAt())
                .openedAt(request.getOpenedAt())
                .clickedAt(request.getClickedAt())
                .reviewedAt(request.getReviewedAt())
                .createdAt(request.getCreatedAt())
                .errorMessage(request.getErrorMessage())
                .build();
    }
}