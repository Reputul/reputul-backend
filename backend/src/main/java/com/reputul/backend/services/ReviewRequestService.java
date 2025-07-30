package com.reputul.backend.services;

import com.reputul.backend.dto.EmailTemplateDto;
import com.reputul.backend.dto.ReviewRequestDto;
import com.reputul.backend.dto.SendReviewRequestDto;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        if (!customer.getBusiness().getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied: Customer does not belong to your business");
        }

        // Get template and business
        EmailTemplate template = emailTemplateRepository.findById(request.getTemplateId())
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Business business = customer.getBusiness();

        // UPDATED: Use EmailTemplateService to get proper processed content
        String processedSubject = emailTemplateService.renderTemplateSubject(user, template.getId(), customer);
        String processedBody = emailTemplateService.renderTemplate(user, template.getId(), customer);

        // Generate review link (kept for backward compatibility)
        String reviewLink = emailService.generateReviewLink(business);

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

        // UPDATED: Use the new template-based email sending
        boolean emailSent = sendEmailWithTemplate(customer, template, reviewRequest);

        // Update status based on send result
        if (emailSent) {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
            reviewRequest.setSentAt(LocalDateTime.now());
            log.info("✅ Review request sent successfully to {}", customer.getEmail());
        } else {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.FAILED);
            reviewRequest.setErrorMessage("Failed to send email via SendGrid");
            log.error("❌ Failed to send review request to {}", customer.getEmail());
        }

        reviewRequest = reviewRequestRepository.save(reviewRequest);
        return convertToDto(reviewRequest);
    }

    /**
     * NEW: Send review request with default template (recommended for new integrations)
     */
    @Transactional
    public ReviewRequestDto sendReviewRequestWithDefaultTemplate(User user, Long customerId) {
        log.info("Sending review request with default template for customer {}", customerId);

        // Validate customer belongs to user's business
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (!customer.getBusiness().getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied: Customer does not belong to your business");
        }

        Business business = customer.getBusiness();

        // Get default template
        EmailTemplate template;
        try {
            template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, EmailTemplate.TemplateType.INITIAL_REQUEST)
                    .orElseThrow(() -> new RuntimeException("No default template found"));
        } catch (Exception e) {
            log.warn("No default template found, creating default templates for user {}", user.getId());
            emailTemplateService.createDefaultTemplatesForUser(user);
            template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, EmailTemplate.TemplateType.INITIAL_REQUEST)
                    .orElseThrow(() -> new RuntimeException("Failed to create default template"));
        }

        // Process template content
        String processedSubject = emailTemplateService.renderTemplateSubject(user, template.getId(), customer);
        String processedBody = emailTemplateService.renderTemplate(user, template.getId(), customer);
        String reviewLink = emailService.generateReviewLink(business);

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

        // Send email using new template-based method
        boolean emailSent = emailService.sendReviewRequestWithTemplate(customer);

        // Update status
        if (emailSent) {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
            reviewRequest.setSentAt(LocalDateTime.now());
            log.info("✅ Default template review request sent successfully to {}", customer.getEmail());
        } else {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.FAILED);
            reviewRequest.setErrorMessage("Failed to send email with default template");
            log.error("❌ Failed to send default template review request to {}", customer.getEmail());
        }

        reviewRequest = reviewRequestRepository.save(reviewRequest);
        return convertToDto(reviewRequest);
    }

    /**
     * NEW: Send follow-up emails
     */
    @Transactional
    public ReviewRequestDto sendFollowUpEmail(User user, Long customerId, EmailTemplate.TemplateType followUpType) {
        log.info("Sending {} follow-up email for customer {}", followUpType, customerId);

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        if (!customer.getBusiness().getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Access denied: Customer does not belong to your business");
        }

        // Get follow-up template
        EmailTemplate template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, followUpType)
                .orElseThrow(() -> new RuntimeException("No default template found for type: " + followUpType));

        Business business = customer.getBusiness();
        String processedSubject = emailTemplateService.renderTemplateSubject(user, template.getId(), customer);
        String processedBody = emailTemplateService.renderTemplate(user, template.getId(), customer);
        String reviewLink = emailService.generateReviewLink(business);

        // Create follow-up request record
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

        // Send follow-up email
        boolean emailSent = emailService.sendFollowUpEmail(customer, followUpType);

        // Update status
        if (emailSent) {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
            reviewRequest.setSentAt(LocalDateTime.now());
            log.info("✅ Follow-up email sent successfully to {}", customer.getEmail());
        } else {
            reviewRequest.setStatus(ReviewRequest.RequestStatus.FAILED);
            reviewRequest.setErrorMessage("Failed to send follow-up email");
            log.error("❌ Failed to send follow-up email to {}", customer.getEmail());
        }

        reviewRequest = reviewRequestRepository.save(reviewRequest);
        return convertToDto(reviewRequest);
    }

    /**
     * HELPER: Determine which email sending method to use based on template type
     */
    private boolean sendEmailWithTemplate(Customer customer, EmailTemplate template, ReviewRequest reviewRequest) {
        try {
            // Use the new template-based methods for better HTML rendering
            switch (template.getType()) {
                case INITIAL_REQUEST:
                    return emailService.sendReviewRequestWithTemplate(customer);
                case FOLLOW_UP_3_DAY:
                    return emailService.sendFollowUpEmail(customer, EmailTemplate.TemplateType.FOLLOW_UP_3_DAY);
                case FOLLOW_UP_7_DAY:
                    return emailService.sendFollowUpEmail(customer, EmailTemplate.TemplateType.FOLLOW_UP_7_DAY);
                case THANK_YOU:
                    return emailService.sendThankYouEmail(customer);
                default:
                    // Fallback to old method for custom templates
                    log.warn("Using fallback email method for template type: {}", template.getType());
                    return emailService.sendReviewRequest(
                            customer,
                            customer.getBusiness(),
                            reviewRequest.getSubject(),
                            reviewRequest.getEmailBody(),
                            reviewRequest.getReviewLink()
                    );
            }
        } catch (Exception e) {
            log.error("Error sending email with template, falling back to old method: {}", e.getMessage());
            // Ultimate fallback
            return emailService.sendReviewRequest(
                    customer,
                    customer.getBusiness(),
                    reviewRequest.getSubject(),
                    reviewRequest.getEmailBody(),
                    reviewRequest.getReviewLink()
            );
        }
    }

    public List<ReviewRequestDto> getReviewRequestsByBusiness(User user, Long businessId) {
        // Verify business belongs to user
        Business business = businessRepository.findByIdAndUserId(businessId, user.getId())
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

    /**
     * NEW: Batch send review requests to multiple customers
     */
    @Transactional
    public List<ReviewRequestDto> sendBulkReviewRequests(User user, List<Long> customerIds) {
        log.info("Sending bulk review requests to {} customers", customerIds.size());

        return customerIds.stream()
                .map(customerId -> {
                    try {
                        return sendReviewRequestWithDefaultTemplate(user, customerId);
                    } catch (Exception e) {
                        log.error("Failed to send review request to customer {}: {}", customerId, e.getMessage());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * NEW: Test email functionality
     */
    public boolean sendTestEmail(String toEmail) {
        try {
            return emailService.sendTestEmail(toEmail, "Test Email from Reputul", null);
        } catch (Exception e) {
            log.error("Failed to send test email to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    /**
     * NEW: Force update templates to HTML versions
     */
    public String forceUpdateTemplates(User user) {
        try {
            log.info("Force updating SYSTEM templates for user {} (preserving user-created templates)", user.getId());
            emailTemplateService.forceCreateDefaultTemplatesForUser(user);
            return "System HTML templates updated successfully for user: " + user.getId() + " (user templates preserved)";
        } catch (Exception e) {
            log.error("Failed to update templates for user {}: {}", user.getId(), e.getMessage());
            return "Error updating templates: " + e.getMessage();
        }
    }

    /**
     * NEW: Get template statistics for user
     */
    public Map<String, Object> getTemplateStats(User user) {
        try {
            List<EmailTemplateDto> allTemplates = emailTemplateService.getAllTemplatesByUser(user);
            List<EmailTemplateDto> userTemplates = emailTemplateService.getUserCreatedTemplates(user);
            List<EmailTemplateDto> systemTemplates = emailTemplateService.getSystemTemplates(user);

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalTemplates", allTemplates.size());
            stats.put("userCreatedTemplates", userTemplates.size());
            stats.put("systemTemplates", systemTemplates.size());
            stats.put("hasHtmlTemplates", allTemplates.stream().anyMatch(t -> t.getIsHtml()));
            stats.put("templatesByType", allTemplates.stream()
                    .collect(Collectors.groupingBy(
                            t -> t.getType().toString(),
                            Collectors.counting())));

            return stats;
        } catch (Exception e) {
            log.error("Failed to get template stats for user {}: {}", user.getId(), e.getMessage());
            Map<String, Object> errorStats = new HashMap<>();
            errorStats.put("error", e.getMessage());
            return errorStats;
        }
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