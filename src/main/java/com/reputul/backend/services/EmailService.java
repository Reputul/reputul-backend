package com.reputul.backend.services;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Service
@Slf4j
public class EmailService {

    private final SendGrid sendGrid;
    private final EmailTemplateService emailTemplateService;

    @Value("${sendgrid.from.email}")
    private String fromEmail;

    @Value("${sendgrid.from.name}")
    private String fromName;

    @Value("${app.base.url:http://localhost:3000}")
    private String baseUrl;

    public EmailService(@Value("${sendgrid.api.key}") String apiKey, EmailTemplateService emailTemplateService) {
        log.info("Initializing EmailService with API key: {}...",
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) : "null");
        this.sendGrid = new SendGrid(apiKey);
        this.emailTemplateService = emailTemplateService;
    }

    /**
     * GOOGLE COMPLIANT: Send review request using EmailTemplateService
     * Templates now always show ALL review options to ALL customers
     */
    public boolean sendReviewRequestWithTemplate(Customer customer) {
        log.info("Sending COMPLIANT review request with template to: {}", customer.getEmail());

        try {
            Business business = customer.getBusiness();

            // Get processed HTML content from EmailTemplateService
            String htmlContent = emailTemplateService.processTemplateWithCustomer(customer);

            // Get processed subject
            Long templateId = getDefaultTemplateId(customer, EmailTemplate.TemplateType.INITIAL_REQUEST);
            String subject = emailTemplateService.renderTemplateSubject(
                    business.getUser(), templateId, customer);

            // Send via SendGrid
            return sendHtmlEmail(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName()
            );

        } catch (Exception e) {
            log.error("Failed to send compliant template-based review request to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * GOOGLE COMPLIANT: Send follow-up emails (3-day, 7-day)
     * All follow-ups show ALL review options
     */
    public boolean sendFollowUpEmail(Customer customer, EmailTemplate.TemplateType followUpType) {
        log.info("Sending COMPLIANT {} follow-up email to: {}", followUpType, customer.getEmail());

        try {
            Business business = customer.getBusiness();
            Long templateId = getDefaultTemplateId(customer, followUpType);

            String htmlContent = emailTemplateService.renderTemplate(business.getUser(), templateId, customer);
            String subject = emailTemplateService.renderTemplateSubject(business.getUser(), templateId, customer);

            return sendHtmlEmail(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName()
            );

        } catch (Exception e) {
            log.error("Failed to send compliant follow-up email to: {}", customer.getEmail(), e);
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
            Long templateId = getDefaultTemplateId(customer, EmailTemplate.TemplateType.THANK_YOU);

            String htmlContent = emailTemplateService.renderTemplate(business.getUser(), templateId, customer);
            String subject = emailTemplateService.renderTemplateSubject(business.getUser(), templateId, customer);

            return sendHtmlEmail(
                    customer.getEmail(),
                    customer.getName(),
                    subject,
                    htmlContent,
                    business.getName()
            );

        } catch (Exception e) {
            log.error("Failed to send thank you email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * UPDATED: Legacy method - now uses COMPLIANT EmailTemplateService processing
     */
    public boolean sendReviewRequest(Customer customer, Business business, String subject, String body, String reviewLink) {
        log.info("Attempting to send COMPLIANT review request to: {}", customer.getEmail());
        log.info("From email: {}, From name: {}", fromEmail, business.getName());

        try {
            // Use COMPLIANT EmailTemplateService for better template processing
            String processedBody = emailTemplateService.processTemplate(body, customer, business, reviewLink);
            String processedSubject = emailTemplateService.processTemplate(subject, customer, business, reviewLink);

            log.info("Processed subject: {}", processedSubject);
            log.info("Processed body preview: {}...",
                    processedBody.length() > 100 ? processedBody.substring(0, 100) : processedBody);

            return sendHtmlEmail(
                    customer.getEmail(),
                    customer.getName(),
                    processedSubject,
                    processedBody,
                    business.getName()
            );

        } catch (Exception e) {
            log.error("❌ Error sending compliant review request to {}: {}", customer.getEmail(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * CORE METHOD: Send HTML email via SendGrid
     */
    private boolean sendHtmlEmail(String toEmail, String toName, String subject, String htmlContent, String fromBusinessName) {
        try {
            Email from = new Email(fromEmail, fromBusinessName != null ? fromBusinessName : fromName);
            Email to = new Email(toEmail, toName);

            Content content = new Content("text/html", htmlContent);
            Mail mail = new Mail(from, subject, to, content);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            log.info("Sending COMPLIANT HTML email to SendGrid...");
            Response response = sendGrid.api(request);

            log.info("SendGrid Response - Status: {}", response.getStatusCode());
            log.info("SendGrid Response - Body: {}", response.getBody());

            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("✅ COMPLIANT HTML email sent successfully to {}", toEmail);
            } else {
                log.error("❌ SendGrid failed with status {}: {}", response.getStatusCode(), response.getBody());
            }

            return success;

        } catch (IOException e) {
            log.error("❌ IOException sending HTML email to {}: {}", toEmail, e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("❌ Unexpected error sending HTML email to {}: {}", toEmail, e.getMessage(), e);
            return false;
        }
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
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Google Compliant Test Email</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #f4f4f4;">
                <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                    <div style="background-color: #2563eb; color: white; padding: 20px; text-align: center;">
                        <h1 style="margin: 0;">Google Compliant Test</h1>
                        <p style="margin: 5px 0 0 0;">All Options Always Available</p>
                    </div>
                    <div style="padding: 30px 20px;">
                        <p>This is a Google-compliant test email. ALL review options are shown to ALL customers.</p>
                        
                        <div style="background-color: #f8f9fa; padding: 25px; border-radius: 8px; text-align: center; margin: 25px 0;">
                            <h2 style="margin: 0 0 15px 0; color: #374151;">Choose Your Preferred Platform</h2>
                            <p style="margin: 0 0 20px 0; color: #6b7280; font-size: 14px;">All options are always available to ensure Google compliance:</p>
                            
                            <div style="margin-bottom: 15px;">
                                <a href="https://google.com" style="display: inline-block; background-color: #16a34a; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    🌟 Google Review (Always Available)
                                </a>
                            </div>
                            
                            <div style="margin-bottom: 15px;">
                                <a href="https://facebook.com" style="display: inline-block; background-color: #1877f2; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    📘 Facebook Review (Always Available)
                                </a>
                            </div>
                            
                            <div style="margin-bottom: 15px;">
                                <a href="https://yelp.com" style="display: inline-block; background-color: #d32323; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    ⭐ Yelp Review (Always Available)
                                </a>
                            </div>
                            
                            <div style="margin-bottom: 15px;">
                                <a href="https://localhost:3000/feedback/test" style="display: inline-block; background-color: #6b7280; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    💬 Private Feedback (Always Available)
                                </a>
                            </div>

                            <p style="margin: 20px 0 0 0; color: #6b7280; font-size: 12px;">
                                ✅ Google Compliant: All platforms equally accessible to all customers
                            </p>
                        </div>
                        
                        <p><strong>Google Compliance Features:</strong></p>
                        <ul style="color: #666; font-size: 14px;">
                            <li>No rating-based filtering</li>
                            <li>Equal platform access for all customers</li>
                            <li>Honest messaging that doesn't manipulate reviews</li>
                            <li>All review options always visible</li>
                        </ul>
                    </div>
                </div>
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
     * This allows customers to choose their experience level first, then see appropriate options
     */
    public String generateReviewLink(Business business) {
        // COMPLIANT: Link goes to rating gate, not direct platforms
        return String.format("%s/feedback-gate/business/%d", baseUrl, business.getId());
    }

    /**
     * Send password reset email (unchanged - not related to review compliance)
     */
    public boolean sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("Sending password reset email to: {}", toEmail);

        try {
            String resetUrl = baseUrl + "/reset-password?token=" + resetToken;
            String subject = "Reset Your Reputul Password";
            String htmlContent = createPasswordResetEmailBody(resetUrl);

            return sendHtmlEmail(
                    toEmail,
                    "", // No specific name needed
                    subject,
                    htmlContent,
                    fromName // Use the configured fromName
            );

        } catch (Exception e) {
            log.error("❌ Failed to send password reset email to: {}", toEmail, e);
            return false;
        }
    }

    /**
     * Password reset email HTML template (unchanged)
     */
    private String createPasswordResetEmailBody(String resetUrl) {
        return String.format("""
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Reset Your Password</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #f4f4f4;">
            <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 20px rgba(0,0,0,0.1);">
                <!-- Header -->
                <div style="background: linear-gradient(135deg, #3b82f6 0%%, #8b5cf6 50%%, #6366f1 100%%); color: white; padding: 40px 20px; text-align: center;">
                    <div style="background-color: rgba(255,255,255,0.2); width: 80px; height: 80px; border-radius: 50%%; margin: 0 auto 20px; display: flex; align-items: center; justify-content: center;">
                        <div style="font-size: 36px; font-weight: 900;">R</div>
                    </div>
                    <h1 style="margin: 0; font-size: 28px; font-weight: bold;">Reset Your Password</h1>
                    <p style="margin: 10px 0 0 0; font-size: 18px; opacity: 0.9;">Reputul Account Security</p>
                </div>
                
                <!-- Content -->
                <div style="padding: 40px 30px;">
                    <p style="font-size: 16px; line-height: 1.6; color: #374151; margin-bottom: 20px;">
                        You requested a password reset for your Reputul account. No worries, it happens to the best of us!
                    </p>
                    
                    <p style="font-size: 16px; line-height: 1.6; color: #374151; margin-bottom: 30px;">
                        Click the button below to securely reset your password:
                    </p>
                    
                    <!-- Reset Button -->
                    <div style="text-align: center; margin: 40px 0;">
                        <a href="%s" style="display: inline-block; background: linear-gradient(135deg, #3b82f6 0%%, #6366f1 100%%); color: white; text-decoration: none; padding: 16px 32px; border-radius: 8px; font-weight: bold; font-size: 16px; box-shadow: 0 4px 15px rgba(59, 130, 246, 0.3); transition: all 0.3s ease;">
                            🔒 Reset My Password
                        </a>
                    </div>
                    
                    <!-- Alternative Link -->
                    <div style="background-color: #f9fafb; border-radius: 8px; padding: 20px; margin: 30px 0;">
                        <p style="font-size: 14px; color: #6b7280; margin-bottom: 10px;">
                            Or copy and paste this link in your browser:
                        </p>
                        <p style="font-size: 14px; word-break: break-all; color: #3b82f6; margin: 0;">
                            <a href="%s" style="color: #3b82f6;">%s</a>
                        </p>
                    </div>
                    
                    <!-- Security Notice -->
                    <div style="border-left: 4px solid #fbbf24; background-color: #fefbf2; padding: 16px; margin: 25px 0; border-radius: 0 8px 8px 0;">
                        <p style="font-size: 14px; color: #92400e; margin: 0; font-weight: 500;">
                            ⚠️ <strong>Security Notice:</strong> This link will expire in 1 hour for your protection.
                        </p>
                    </div>
                    
                    <p style="font-size: 14px; line-height: 1.5; color: #6b7280; margin: 20px 0 0 0;">
                        If you didn't request this password reset, please ignore this email. Your password will remain unchanged.
                    </p>
                </div>
                
                <!-- Footer -->
                <div style="background-color: #f9fafb; padding: 30px; text-align: center; border-top: 1px solid #e5e7eb;">
                    <p style="font-size: 14px; color: #6b7280; margin: 0 0 10px 0;">
                        This email was sent by <strong>Reputul</strong>
                    </p>
                    <p style="font-size: 12px; color: #9ca3af; margin: 0;">
                        Helping contractors build better reputations, one review at a time.
                    </p>
                </div>
            </div>
        </body>
        </html>
        """, resetUrl, resetUrl, resetUrl);
    }
}