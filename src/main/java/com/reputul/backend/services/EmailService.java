package com.reputul.backend.services;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.Usage;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.UsageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * EmailService with Resend Integration
 *
 * Resend provides better deliverability and developer experience.
 * https://resend.com
 */
@Service
@Slf4j
public class EmailService {

    private final Resend resend;
    private final boolean enabled;

    private final EmailTemplateService emailTemplateService;
    private final ReviewRequestRepository reviewRequestRepository;
    private final UsageRepository usageRepository;

    @Value("${resend.from-email:reviews@reputul.com}")
    private String fromEmail;

    @Value("${resend.from-name:Reputul}")
    private String fromName;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public EmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.enabled:true}") boolean enabledConfig,
            EmailTemplateService emailTemplateService,
            ReviewRequestRepository reviewRequestRepository,
            UsageRepository usageRepository) {

        this.emailTemplateService = emailTemplateService;
        this.reviewRequestRepository = reviewRequestRepository;
        this.usageRepository = usageRepository;

        // Initialize Resend
        boolean hasValidKey = apiKey != null && !apiKey.isEmpty() && apiKey.startsWith("re_");
        this.enabled = enabledConfig && hasValidKey;

        if (this.enabled) {
            this.resend = new Resend(apiKey);
            log.info("✅ Resend email service initialized");
            log.info("📧 From: {} <{}>", fromName, fromEmail);
        } else {
            this.resend = null;
            if (!hasValidKey) {
                log.warn("⚠️ Resend API key not configured or invalid - emails will not be sent");
                log.warn("   Set RESEND_API_KEY environment variable with your Resend API key (starts with 're_')");
            } else {
                log.warn("⚠️ Resend is disabled via configuration");
            }
        }
    }

    // ========================================================================
    // MAIN PUBLIC METHODS - Used by ReviewRequestService and other services
    // ========================================================================

    /**
     * GOOGLE COMPLIANT: Send review request using EmailTemplateService
     * Templates now always show ALL review options to ALL customers
     */
    public boolean sendReviewRequestWithTemplate(Customer customer) {
        log.info("Sending review request with template to: {}", customer.getEmail());

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

            // Send via Resend
            EmailResult result = sendHtmlEmail(
                    customer.getEmail(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store message ID for tracking
            if (result.isSuccess() && result.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, result.getMessageId());
            }

            return result.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send template-based review request to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Send follow-up emails (3-day, 7-day)
     * All follow-ups show ALL review options
     */
    public boolean sendFollowUpEmail(Customer customer, EmailTemplate.TemplateType followUpType) {
        log.info("Sending {} follow-up email to: {}", followUpType, customer.getEmail());

        try {
            Business business = customer.getBusiness();
            Organization organization = business.getOrganization();
            Long templateId = getDefaultTemplateId(customer, followUpType);

            String htmlContent = emailTemplateService.renderTemplate(business.getUser(), templateId, customer);
            String subject = emailTemplateService.renderTemplateSubject(business.getUser(), templateId, customer);

            // Track email usage for billing
            trackEmailUsage(organization, "FOLLOW_UP");

            // Send via Resend
            EmailResult result = sendHtmlEmail(
                    customer.getEmail(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store message ID
            if (result.isSuccess() && result.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, result.getMessageId());
            }

            return result.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send follow-up email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Send thank you email
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

            // Send via Resend
            EmailResult result = sendHtmlEmail(
                    customer.getEmail(),
                    subject,
                    htmlContent,
                    business.getName(),
                    organization.getId()
            );

            // Store message ID
            if (result.isSuccess() && result.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, result.getMessageId());
            }

            return result.isSuccess();

        } catch (Exception e) {
            log.error("Failed to send thank you email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String toEmail, String token) {
        log.info("Sending password reset email to: {}", toEmail);

        String resetLink = frontendUrl + "/reset-password?token=" + token;
        String subject = "Reset Your Reputul Password";

        String htmlContent = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
        </head>
        <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f4f4f5;">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color: #f4f4f5; padding: 40px 20px;">
                <tr>
                    <td align="center">
                        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width: 480px; background-color: #ffffff; border-radius: 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);">
                            <tr>
                                <td style="padding: 32px 32px 24px; text-align: center; border-bottom: 1px solid #e4e4e7;">
                                    <h1 style="margin: 0; font-size: 24px; font-weight: 700; color: #4F46E5;">Reputul</h1>
                                </td>
                            </tr>
                            <tr>
                                <td style="padding: 32px;">
                                    <h2 style="margin: 0 0 16px; font-size: 20px; font-weight: 600; color: #18181b;">Reset Your Password</h2>
                                    <p style="margin: 0 0 24px; font-size: 15px; line-height: 1.6; color: #52525b;">
                                        We received a request to reset your password. Click the button below to choose a new password.
                                    </p>
                                    <a href="%s" style="display: inline-block; padding: 12px 24px; background-color: #4F46E5; color: #ffffff; text-decoration: none; border-radius: 8px; font-weight: 600; font-size: 15px;">
                                        Reset Password
                                    </a>
                                    <p style="margin: 24px 0 0; font-size: 13px; color: #71717a;">
                                        This link expires in 1 hour. If you didn't request this, you can safely ignore this email.
                                    </p>
                                </td>
                            </tr>
                            <tr>
                                <td style="padding: 24px 32px; border-top: 1px solid #e4e4e7; text-align: center;">
                                    <p style="margin: 0; font-size: 12px; color: #a1a1aa;">
                                        © 2024 Reputul. All rights reserved.
                                    </p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """.formatted(resetLink);

        EmailResult result = sendHtmlEmail(toEmail, subject, htmlContent, null, null);
        return result.isSuccess();
    }

    /**
     * Send review request (legacy method - for backward compatibility)
     */
    public boolean sendReviewRequest(Customer customer, Business business, String subject,
                                     String processedBody, String reviewLink) {
        log.info("Sending review request to: {}", customer.getEmail());

        try {
            Organization organization = business.getOrganization();

            // Track email usage for billing
            trackEmailUsage(organization, "REVIEW_REQUEST");

            // Send via Resend
            EmailResult result = sendHtmlEmail(
                    customer.getEmail(),
                    subject,
                    processedBody,
                    business.getName(),
                    organization.getId()
            );

            // Store message ID
            if (result.isSuccess() && result.getMessageId() != null) {
                updateReviewRequestWithMessageId(customer, result.getMessageId());
            }

            return result.isSuccess();

        } catch (Exception e) {
            log.error("❌ Error sending review request to {}: {}", customer.getEmail(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Send a test email
     */
    public boolean sendTestEmail(String toEmail, String subject, String body) {
        log.info("=== SENDING TEST EMAIL ===");
        log.info("To: {}", toEmail);

        try {
            String testBody = (body != null && !body.trim().isEmpty()) ? body : createTestEmailBody();
            String testSubject = (subject != null && !subject.trim().isEmpty()) ? subject : "Test Email from Reputul";

            EmailResult result = sendHtmlEmail(toEmail, testSubject, testBody, "Reputul", null);
            return result.isSuccess();

        } catch (Exception e) {
            log.error("❌ Error sending test email: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Generate review link for a business
     */
    public String generateReviewLink(Business business) {
        // If Google Place ID exists, use Google review link
        if (business.getGooglePlaceId() != null && !business.getGooglePlaceId().isEmpty()) {
            return "https://search.google.com/local/writereview?placeid=" + business.getGooglePlaceId();
        }

        // Otherwise, use internal feedback gate
        return frontendUrl + "/feedback-gate/business/" + business.getId();
    }

    // ========================================================================
    // CORE SENDING METHOD
    // ========================================================================

    /**
     * Core method to send HTML email via Resend
     */
    private EmailResult sendHtmlEmail(String toEmail, String subject, String htmlContent,
                                      String fromBusinessName, Long organizationId) {

        if (!enabled) {
            log.warn("📧 Resend DISABLED - Would send to: {} | Subject: {}", toEmail, subject);
            return new EmailResult(false, null, "Resend service is disabled - set RESEND_API_KEY");
        }

        if (toEmail == null || toEmail.trim().isEmpty()) {
            log.error("❌ Cannot send email - recipient is empty");
            return new EmailResult(false, null, "Recipient email is required");
        }

        if (subject == null || subject.trim().isEmpty()) {
            log.error("❌ Cannot send email - subject is empty");
            return new EmailResult(false, null, "Subject is required");
        }

        try {
            // Ensure CAN-SPAM compliance
            String compliantHtmlContent = ensureCanSpamCompliance(htmlContent, organizationId);

            // Build sender name
            String senderName = fromBusinessName != null ? fromBusinessName + " via Reputul" : fromName;
            String from = String.format("%s <%s>", senderName, fromEmail);

            log.info("📧 Sending email via Resend:");
            log.info("   From: {}", from);
            log.info("   To: {}", toEmail);
            log.info("   Subject: {}", subject);

            CreateEmailOptions options = CreateEmailOptions.builder()
                    .from(from)
                    .to(toEmail)
                    .subject(subject)
                    .html(compliantHtmlContent)
                    .build();

            CreateEmailResponse response = resend.emails().send(options);
            String messageId = response.getId();

            log.info("✅ Email sent successfully via Resend - Message ID: {}", messageId);
            return new EmailResult(true, messageId, null);

        } catch (ResendException e) {
            log.error("❌ Resend API error: {}", e.getMessage());
            return new EmailResult(false, null, "Resend API error: " + e.getMessage());
        } catch (Exception e) {
            log.error("❌ Unexpected error sending email: {}", e.getMessage(), e);
            return new EmailResult(false, null, "Unexpected error: " + e.getMessage());
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Get default template ID for a customer and template type
     */
    private Long getDefaultTemplateId(Customer customer, EmailTemplate.TemplateType type) {
        try {
            return emailTemplateService.getDefaultTemplate(customer.getBusiness().getUser(), type).getId();
        } catch (Exception e) {
            log.warn("Could not get default template ID for type {}: {}", type, e.getMessage());
            return null;
        }
    }

    /**
     * Update review request with message ID for tracking
     * Uses existing repository method that filters by PENDING status
     */
    private void updateReviewRequestWithMessageId(Customer customer, String messageId) {
        try {
            reviewRequestRepository.findTopByCustomerAndStatusOrderByCreatedAtDesc(
                    customer,
                    com.reputul.backend.models.ReviewRequest.RequestStatus.PENDING
            ).ifPresent(reviewRequest -> {
                reviewRequest.setSendgridMessageId(messageId); // Reusing field name for any provider
                reviewRequestRepository.save(reviewRequest);
                log.debug("Updated review request {} with message ID: {}",
                        reviewRequest.getId(), messageId);
            });
        } catch (Exception e) {
            log.warn("Could not update review request with message ID: {}", e.getMessage());
        }
    }

    /**
     * Ensure CAN-SPAM compliance by adding required footer
     */
    private String ensureCanSpamCompliance(String htmlContent, Long organizationId) {
        // Check if already has unsubscribe link
        if (htmlContent != null && (htmlContent.contains("unsubscribe") || htmlContent.contains("Unsubscribe"))) {
            return htmlContent;
        }

        String unsubscribeUrl = organizationId != null ?
                String.format("%s/unsubscribe?org=%d", frontendUrl, organizationId) :
                String.format("%s/unsubscribe", frontendUrl);

        String canSpamFooter = String.format("""
            <!-- CAN-SPAM Footer -->
            <div style="margin-top: 40px; padding-top: 20px; border-top: 1px solid #e5e7eb; text-align: center; font-size: 12px; color: #6b7280;">
                <p style="margin: 0 0 8px 0;">
                    You received this email because you did business with us.
                </p>
                <p style="margin: 0;">
                    <a href="%s" style="color: #6b7280; text-decoration: underline;">Unsubscribe</a>
                    &nbsp;|&nbsp;
                    <a href="%s/privacy" style="color: #6b7280; text-decoration: underline;">Privacy Policy</a>
                </p>
            </div>
            """, unsubscribeUrl, frontendUrl);

        if (htmlContent != null && htmlContent.contains("</body>")) {
            return htmlContent.replace("</body>", canSpamFooter + "</body>");
        } else {
            return (htmlContent != null ? htmlContent : "") + canSpamFooter;
        }
    }

    /**
     * Create a test email body
     */
    private String createTestEmailBody() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background-color: #f4f4f5;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color: #f4f4f5; padding: 40px 20px;">
                    <tr>
                        <td align="center">
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width: 480px; background-color: #ffffff; border-radius: 12px; box-shadow: 0 4px 6px rgba(0, 0, 0, 0.05);">
                                <tr>
                                    <td style="padding: 32px 32px 24px; text-align: center; border-bottom: 1px solid #e4e4e7;">
                                        <h1 style="margin: 0; font-size: 24px; font-weight: 700; color: #4F46E5;">Reputul</h1>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding: 32px;">
                                        <h2 style="margin: 0 0 16px; font-size: 20px; font-weight: 600; color: #18181b;">✅ Email Integration Test</h2>
                                        <p style="margin: 0 0 16px; font-size: 15px; line-height: 1.6; color: #52525b;">
                                            If you're reading this, your Resend email integration is working correctly!
                                        </p>
                                        <p style="margin: 0; font-size: 13px; color: #71717a;">
                                            Provider: Resend<br>
                                            Time: %s
                                        </p>
                                    </td>
                                </tr>
                                <tr>
                                    <td style="padding: 24px 32px; border-top: 1px solid #e4e4e7; text-align: center;">
                                        <p style="margin: 0; font-size: 12px; color: #a1a1aa;">
                                            © 2024 Reputul. All rights reserved.
                                        </p>
                                    </td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
            </body>
            </html>
            """.formatted(java.time.Instant.now().toString());
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
            log.debug("Tracked email usage for org {} - type: {}", organization.getId(), emailType);
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

    /**
     * Check if email service is configured and ready
     */
    public boolean isConfigured() {
        return enabled;
    }

    /**
     * Get the active email provider name
     */
    public String getActiveProvider() {
        return enabled ? "Resend" : "None";
    }

    /**
     * Get the configured from email address
     */
    public String getFromEmail() {
        return fromEmail;
    }

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    /**
     * Result wrapper for email operations
     */
    private static class EmailResult {
        private final boolean success;
        private final String messageId;
        private final String errorMessage;

        public EmailResult(boolean success, String messageId, String errorMessage) {
            this.success = success;
            this.messageId = messageId;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}