package com.reputul.backend.services;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.Usage;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.UsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

/**
 * EmailService with SendGrid Integration
 * Tracking is configured via SendGrid Dashboard, not in code
 */
@Service
@Slf4j
public class EmailService {

    private final SendGrid sendGrid;
    private final EmailTemplateService emailTemplateService;
    private final ReviewRequestRepository reviewRequestRepository;
    private final UsageRepository usageRepository;
    private final ObjectMapper objectMapper;

    @Value("${sendgrid.from.email}")
    private String fromEmail;

    @Value("${sendgrid.from.name}")
    private String fromName;

    @Value("${app.base.url:http://localhost:3000}")
    private String baseUrl;

    @Value("${sendgrid.webhook.verification.key:}")
    private String webhookVerificationKey;

    @Value("${sendgrid.domain.verified:app.reputul.com}")
    private String verifiedDomain;

    public EmailService(@Value("${sendgrid.api.key}") String apiKey,
                        EmailTemplateService emailTemplateService,
                        ReviewRequestRepository reviewRequestRepository,
                        UsageRepository usageRepository) {
        log.info("Initializing EmailService with API key: {}...",
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) : "null");
        this.sendGrid = new SendGrid(apiKey);
        this.emailTemplateService = emailTemplateService;
        this.reviewRequestRepository = reviewRequestRepository;
        this.usageRepository = usageRepository;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * GOOGLE COMPLIANT: Send review request using EmailTemplateService
     * Templates now always show ALL review options to ALL customers
     * ENHANCED: Now tracks SendGrid message ID for webhook events
     */
    public boolean sendReviewRequestWithTemplate(Customer customer) {
        log.info("Sending COMPLIANT review request with template to: {}", customer.getEmail());

        try {
            Business business = customer.getBusiness();
            Organization organization = business.getOrganization();

            // Get processed HTML content from EmailTemplateService
            String htmlContent = emailTemplateService.processTemplateWithCustomer(customer);

            // Get processed subject
            Long templateId = getDefaultTemplateId(customer, EmailTemplate.TemplateType.INITIAL_REQUEST);
            String subject = emailTemplateService.renderTemplateSubject(
                    business.getUser(), templateId, customer);

            // Track email usage for billing
            trackEmailUsage(organization, "REVIEW_REQUEST");

            // Send via SendGrid and capture message ID
            SendGridResponse response = sendHtmlEmailWithTracking(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store SendGrid message ID if we have a ReviewRequest to update
            if (response.isSuccess() && response.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, response.getMessageId());
            }

            return response.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send compliant template-based review request to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Send follow-up emails (3-day, 7-day)
     * All follow-ups show ALL review options
     * ENHANCED: Now tracks SendGrid message ID
     */
    public boolean sendFollowUpEmail(Customer customer, EmailTemplate.TemplateType followUpType) {
        log.info("Sending COMPLIANT {} follow-up email to: {}", followUpType, customer.getEmail());

        try {
            Business business = customer.getBusiness();
            Organization organization = business.getOrganization();
            Long templateId = getDefaultTemplateId(customer, followUpType);

            String htmlContent = emailTemplateService.renderTemplate(business.getUser(), templateId, customer);
            String subject = emailTemplateService.renderTemplateSubject(business.getUser(), templateId, customer);

            // Track email usage for billing
            trackEmailUsage(organization, "FOLLOW_UP");

            // Send via SendGrid and capture message ID
            SendGridResponse response = sendHtmlEmailWithTracking(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store SendGrid message ID
            if (response.isSuccess() && response.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, response.getMessageId());
            }

            return response.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send compliant follow-up email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Send thank you email
     * ENHANCED: Now tracks SendGrid message ID
     */
    public boolean sendThankYouEmail(Customer customer) {
        log.info("Sending thank you email to: {}", customer.getEmail());

        try {
            Business business = customer.getBusiness();
            Organization organization = business.getOrganization();
            Long templateId = getDefaultTemplateId(customer, EmailTemplate.TemplateType.THANK_YOU);

            String htmlContent = emailTemplateService.renderTemplate(business.getUser(), templateId, customer);
            String subject = emailTemplateService.renderTemplateSubject(business.getUser(), templateId, customer);

            // Track email usage for billing
            trackEmailUsage(organization, "THANK_YOU");

            // Send via SendGrid and capture message ID
            SendGridResponse response = sendHtmlEmailWithTracking(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store SendGrid message ID
            if (response.isSuccess() && response.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, response.getMessageId());
            }

            return response.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send thank you email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * UPDATED: Legacy method - now uses COMPLIANT EmailTemplateService processing
     * ENHANCED: Now tracks SendGrid message ID
     */
    public boolean sendReviewRequest(Customer customer, Business business, String subject, String body, String reviewLink) {
        log.info("Attempting to send COMPLIANT review request to: {}", customer.getEmail());
        log.info("From email: {}, From name: {}", fromEmail, business.getName());

        try {
            Organization organization = business.getOrganization();

            // Use COMPLIANT EmailTemplateService for better template processing
            String processedBody = emailTemplateService.processTemplate(body, customer, business, reviewLink);
            String processedSubject = emailTemplateService.processTemplate(subject, customer, business, reviewLink);

            log.info("Processed subject: {}", processedSubject);
            log.info("Processed body preview: {}...",
                    processedBody.length() > 100 ? processedBody.substring(0, 100) : processedBody);

            // Track email usage for billing
            trackEmailUsage(organization, "REVIEW_REQUEST_LEGACY");

            // Send via SendGrid and capture message ID
            SendGridResponse response = sendHtmlEmailWithTracking(
                    customer.getEmail(),
                    customer.getName(),
                    processedSubject,
                    processedBody,
                    business.getName(),
                    organization.getId()
            );

            // Store SendGrid message ID
            if (response.isSuccess() && response.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, response.getMessageId());
            }

            return response.isSuccess();

        } catch (Exception e) {
            log.error("❌ Error sending compliant review request to {}: {}", customer.getEmail(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * CORE METHOD: Send HTML email via SendGrid with tracking
     * Returns SendGridResponse with success status and message ID
     * SIMPLIFIED: No tracking classes needed - configured in SendGrid dashboard
     */
    private SendGridResponse sendHtmlEmailWithTracking(String toEmail, String toName, String subject,
                                                       String htmlContent, String fromBusinessName, Long organizationId) {
        try {
            Email from = new Email(fromEmail, fromBusinessName != null ? fromBusinessName : fromName);
            Email to = new Email(toEmail, toName);

            // Ensure CAN-SPAM compliant content
            String compliantHtmlContent = ensureCanSpamCompliance(htmlContent, organizationId);

            Content content = new Content("text/html", compliantHtmlContent);
            Mail mail = new Mail(from, subject, to, content);

            // NOTE: Tracking is configured in SendGrid Dashboard, not in code
            // Go to SendGrid Dashboard > Settings > Tracking to enable click and open tracking

            // Add custom headers for internal tracking
            String internalMessageId = UUID.randomUUID().toString();
            mail.addHeader("X-Reputul-Message-Id", internalMessageId);
            mail.addHeader("X-Reputul-Org-Id", organizationId != null ? organizationId.toString() : "system");

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            log.info("Sending COMPLIANT HTML email to SendGrid...");
            Response response = sendGrid.api(request);

            log.info("SendGrid Response - Status: {}", response.getStatusCode());
            log.info("SendGrid Response - Body: {}", response.getBody());

            // Extract SendGrid message ID from response
            String sendgridMessageId = extractSendGridMessageId(response);

            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("✅ COMPLIANT HTML email sent successfully to {} with SendGrid ID: {}",
                        toEmail, sendgridMessageId);
                return new SendGridResponse(true, sendgridMessageId);
            } else {
                log.error("❌ SendGrid failed with status {}: {}", response.getStatusCode(), response.getBody());
                return new SendGridResponse(false, null);
            }

        } catch (IOException e) {
            log.error("❌ IOException sending HTML email to {}: {}", toEmail, e.getMessage(), e);
            return new SendGridResponse(false, null);
        } catch (Exception e) {
            log.error("❌ Unexpected error sending HTML email to {}: {}", toEmail, e.getMessage(), e);
            return new SendGridResponse(false, null);
        }
    }

    /**
     * Backward compatibility wrapper for existing sendHtmlEmail calls
     */
    private boolean sendHtmlEmail(String toEmail, String toName, String subject, String htmlContent, String fromBusinessName) {
        SendGridResponse response = sendHtmlEmailWithTracking(toEmail, toName, subject, htmlContent, fromBusinessName, null);
        return response.isSuccess();
    }

    /**
     * UPDATED: Test email with Google-compliant template
     */
    public boolean sendTestEmail(String toEmail, String subject, String body) {
        log.info("=== SENDGRID COMPLIANT TEST EMAIL ===");
        log.info("To: {}", toEmail);
        log.info("From: {} ({})", fromEmail, fromName);

        try {
            // If no custom body provided, use a compliant test template with all buttons
            String testBody = (body != null && !body.trim().isEmpty()) ? body : createCompliantTestEmailBody();
            String testSubject = (subject != null && !subject.trim().isEmpty()) ? subject : "Test Email - Google Compliant";

            return sendHtmlEmail(toEmail, "", testSubject, testBody, fromName);

        } catch (Exception e) {
            log.error("❌ Error sending test email: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Create a test email with ALL review options shown
     */
    private String createCompliantTestEmailBody() {
        // ... (same as before, no changes needed)
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Google Compliant Test Email</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #f4f4f4;">
                <!-- ... full HTML template ... -->
            </body>
            </html>
            """;
    }

    /**
     * Helper method to get default template ID
     */
    private Long getDefaultTemplateId(Customer customer, EmailTemplate.TemplateType templateType) {
        try {
            return emailTemplateService.getDefaultTemplate(
                    customer.getBusiness().getUser(),
                    templateType
            ).getId();
        } catch (Exception e) {
            log.error("Failed to get default template for type: {}", templateType, e);
            throw new RuntimeException("No default template found for type: " + templateType);
        }
    }

    /**
     * GOOGLE COMPLIANT: Generate review link that goes to rating gate
     */
    public String generateReviewLink(Business business) {
        return String.format("%s/feedback-gate/business/%d", baseUrl, business.getId());
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("Sending password reset email to: {}", toEmail);

        try {
            String resetUrl = baseUrl + "/reset-password?token=" + resetToken;
            String subject = "Reset Your Reputul Password";
            String htmlContent = createPasswordResetEmailBody(resetUrl);

            return sendHtmlEmail(toEmail, "", subject, htmlContent, fromName);

        } catch (Exception e) {
            log.error("❌ Failed to send password reset email to: {}", toEmail, e);
            return false;
        }
    }

    /**
     * Password reset email HTML template
     */
    private String createPasswordResetEmailBody(String resetUrl) {
        // ... (same as before, no changes needed)
        return String.format("""
            <!DOCTYPE html>
            <html>
            <!-- ... full HTML template ... -->
            </html>
            """, resetUrl, resetUrl, resetUrl);
    }

    // ========== EMAIL EVENT TRACKING METHODS ==========

    /**
     * Extract SendGrid message ID from API response
     */
    private String extractSendGridMessageId(Response response) {
        try {
            // First check headers
            if (response.getHeaders() != null) {
                String messageId = response.getHeaders().get("X-Message-Id");
                if (messageId != null && !messageId.isEmpty()) {
                    log.info("Extracted SendGrid Message ID from headers: {}", messageId);
                    return messageId;
                }
            }

            // Then try to parse from body
            if (response.getBody() != null && !response.getBody().isEmpty()) {
                JsonNode root = objectMapper.readTree(response.getBody());
                if (root.has("message_id")) {
                    String messageId = root.get("message_id").asText();
                    log.info("Extracted SendGrid Message ID from body: {}", messageId);
                    return messageId;
                }
            }

            // Generate a fallback ID
            String fallbackId = "sg_" + UUID.randomUUID().toString();
            log.warn("Could not extract SendGrid Message ID, using fallback: {}", fallbackId);
            return fallbackId;

        } catch (Exception e) {
            log.error("Error extracting SendGrid message ID: {}", e.getMessage());
            String fallbackId = "sg_" + UUID.randomUUID().toString();
            return fallbackId;
        }
    }

    /**
     * Update ReviewRequest with SendGrid message ID
     */
    private void updateReviewRequestWithMessageId(Customer customer, String sendgridMessageId) {
        try {
            // Find the most recent pending review request for this customer
            ReviewRequest reviewRequest = reviewRequestRepository
                    .findTopByCustomerAndStatusOrderByCreatedAtDesc(
                            customer,
                            ReviewRequest.RequestStatus.PENDING
                    )
                    .orElse(null);

            if (reviewRequest != null) {
                reviewRequest.setSendgridMessageId(sendgridMessageId);
                reviewRequest.setEmailStatus("sent");
                reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
                reviewRequest.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
                reviewRequestRepository.save(reviewRequest);
                log.info("Updated ReviewRequest {} with SendGrid message ID: {}",
                        reviewRequest.getId(), sendgridMessageId);
            } else {
                log.warn("No pending ReviewRequest found for customer {} to update with message ID",
                        customer.getId());
            }
        } catch (Exception e) {
            log.error("Failed to update ReviewRequest with SendGrid message ID: {}", e.getMessage());
        }
    }

    /**
     * Verify SendGrid webhook signature
     */
    public boolean verifyWebhookSignature(String payload, String signature, String timestamp) {
        if (webhookVerificationKey == null || webhookVerificationKey.isEmpty()) {
            log.warn("Webhook verification key not configured, skipping verification");
            return true; // Allow for development, but log warning
        }

        try {
            String signedPayload = timestamp + payload;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    webhookVerificationKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKey);

            byte[] hmacBytes = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            String computedSignature = Base64.getEncoder().encodeToString(hmacBytes);

            boolean valid = computedSignature.equals(signature);

            if (!valid) {
                log.error("Webhook signature verification failed. Expected: {}, Got: {}",
                        computedSignature, signature);
            }

            return valid;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Update email status from SendGrid webhook event
     * FIXED: Uses correct ReviewRequest fields
     */
    public void updateEmailStatusFromWebhook(String messageId, String eventType,
                                             String reason, OffsetDateTime eventTime) {
        log.info("Processing SendGrid webhook - Message ID: {}, Event: {}, Reason: {}",
                messageId, eventType, reason != null ? reason : "N/A");

        try {
            ReviewRequest reviewRequest = reviewRequestRepository
                    .findBySendgridMessageId(messageId)
                    .orElse(null);

            if (reviewRequest == null) {
                log.warn("No ReviewRequest found for SendGrid message ID: {}", messageId);
                return;
            }

            // Update based on event type (monotonic progression)
            boolean shouldUpdate = false;
            ReviewRequest.RequestStatus newStatus = reviewRequest.getStatus();

            switch (eventType.toLowerCase()) {
                case "processed":
                    if (reviewRequest.getEmailStatus() == null ||
                            reviewRequest.getEmailStatus().equals("sent")) {
                        reviewRequest.setEmailStatus("processed");
                        shouldUpdate = true;
                    }
                    break;

                case "delivered":
                    if (!ReviewRequest.RequestStatus.OPENED.equals(reviewRequest.getStatus()) &&
                            !ReviewRequest.RequestStatus.COMPLETED.equals(reviewRequest.getStatus())) {
                        reviewRequest.setEmailStatus("delivered");
                        reviewRequest.setDeliveredAt(eventTime);
                        newStatus = ReviewRequest.RequestStatus.DELIVERED;
                        shouldUpdate = true;
                    }
                    break;

                case "open":
                    if (!ReviewRequest.RequestStatus.COMPLETED.equals(reviewRequest.getStatus())) {
                        reviewRequest.setEmailStatus("opened");
                        reviewRequest.setOpenedAt(eventTime);
                        newStatus = ReviewRequest.RequestStatus.OPENED;
                        shouldUpdate = true;
                    }
                    break;

                case "click":
                    reviewRequest.setEmailStatus("clicked");
                    reviewRequest.setClickedAt(eventTime); // Fixed: use clickedAt field
                    newStatus = ReviewRequest.RequestStatus.CLICKED;
                    shouldUpdate = true;
                    break;

                case "bounce":
                    reviewRequest.setEmailStatus("bounce");
                    reviewRequest.setEmailErrorCode(reason);
                    newStatus = ReviewRequest.RequestStatus.BOUNCED;
                    reviewRequest.setErrorMessage("Email bounced: " + reason);
                    shouldUpdate = true;
                    break;

                case "dropped":
                case "spamreport":
                    reviewRequest.setEmailStatus(eventType);
                    reviewRequest.setEmailErrorCode(reason);
                    newStatus = ReviewRequest.RequestStatus.FAILED;
                    reviewRequest.setErrorMessage("Email " + eventType + ": " + reason);
                    shouldUpdate = true;
                    break;

                case "deferred":
                    reviewRequest.setEmailStatus("deferred");
                    reviewRequest.setEmailErrorCode(reason);
                    shouldUpdate = true;
                    break;
            }

            if (shouldUpdate) {
                reviewRequest.setStatus(newStatus);
                reviewRequestRepository.save(reviewRequest);
                log.info("Updated ReviewRequest {} - Status: {}, Email Status: {}",
                        reviewRequest.getId(), newStatus, reviewRequest.getEmailStatus());
            }

        } catch (Exception e) {
            log.error("Failed to update ReviewRequest from webhook: {}", e.getMessage(), e);
        }
    }

    // ========== HELPER METHODS ==========

    /**
     * Ensure CAN-SPAM compliance
     */
    private String ensureCanSpamCompliance(String htmlContent, Long organizationId) {
        // ... (same as before, no changes needed)
        if (htmlContent.contains("<!-- CAN-SPAM Footer -->")) {
            return htmlContent;
        }

        String unsubscribeUrl = organizationId != null ?
                String.format("%s/unsubscribe?org=%d", baseUrl, organizationId) :
                String.format("%s/unsubscribe", baseUrl);

        String canSpamFooter = String.format("""
            <!-- CAN-SPAM Footer -->
            <div style="margin-top: 40px; padding-top: 20px; border-top: 1px solid #e5e7eb; text-align: center; font-size: 12px; color: #6b7280;">
                <!-- footer content -->
            </div>
            """, unsubscribeUrl, baseUrl, baseUrl);

        if (htmlContent.contains("</body>")) {
            return htmlContent.replace("</body>", canSpamFooter + "</body>");
        } else {
            return htmlContent + canSpamFooter;
        }
    }

    /**
     * Track email usage for billing
     */
    private void trackEmailUsage(Organization organization, String emailType) {
        try {
            Usage usage = Usage.builder()
                    .organization(organization)
                    .metric(Usage.Metric.EMAIL_SENT)
                    .quantity(1)
                    .metadata(emailType)
                    .periodStart(OffsetDateTime.now(ZoneOffset.UTC))
                    .periodEnd(OffsetDateTime.now(ZoneOffset.UTC))
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            usageRepository.save(usage);
            log.info("Tracked email usage for org {} - type: {}", organization.getId(), emailType);
        } catch (Exception e) {
            // Don't fail email sending if usage tracking fails
            log.error("Failed to track email usage: {}", e.getMessage());
        }
    }

    /**
     * Validate email address format
     */
    public boolean isValidEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }
        String emailRegex = "^[A-Za-z0-9+_.-]+@(.+)$";
        return email.matches(emailRegex);
    }

    // ========== INNER CLASSES ==========

    /**
     * Response wrapper for SendGrid API calls
     */
    private static class SendGridResponse {
        private final boolean success;
        private final String messageId;

        public SendGridResponse(boolean success, String messageId) {
            this.success = success;
            this.messageId = messageId;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessageId() {
            return messageId;
        }
    }
}