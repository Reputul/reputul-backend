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
    private final EmailTemplateService emailTemplateService; // ADD THIS DEPENDENCY

    @Value("${sendgrid.from.email}")
    private String fromEmail;

    @Value("${sendgrid.from.name}")
    private String fromName;

    @Value("${app.base.url}")
    private String baseUrl;

    public EmailService(@Value("${sendgrid.api.key}") String apiKey, EmailTemplateService emailTemplateService) {
        log.info("Initializing EmailService with API key: {}...",
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) : "null");
        this.sendGrid = new SendGrid(apiKey);
        this.emailTemplateService = emailTemplateService;
    }

    /**
     * NEW: Send review request using EmailTemplateService (recommended)
     */
    public boolean sendReviewRequestWithTemplate(Customer customer) {
        log.info("Sending review request with template to: {}", customer.getEmail());

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
            log.error("Failed to send template-based review request to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * NEW: Send follow-up emails (3-day, 7-day)
     */
    public boolean sendFollowUpEmail(Customer customer, EmailTemplate.TemplateType followUpType) {
        log.info("Sending {} follow-up email to: {}", followUpType, customer.getEmail());

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
            log.error("Failed to send follow-up email to: {}", customer.getEmail(), e);
            return false;
        }
    }

    /**
     * NEW: Send thank you email
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
     * UPDATED: Your existing method - now uses EmailTemplateService for better processing
     */
    public boolean sendReviewRequest(Customer customer, Business business, String subject, String body, String reviewLink) {
        log.info("Attempting to send review request to: {}", customer.getEmail());
        log.info("From email: {}, From name: {}", fromEmail, business.getName());

        try {
            // Use EmailTemplateService for better template processing
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
            log.error("❌ Error sending review request to {}: {}", customer.getEmail(), e.getMessage(), e);
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

            log.info("Sending HTML email to SendGrid...");
            Response response = sendGrid.api(request);

            log.info("SendGrid Response - Status: {}", response.getStatusCode());
            log.info("SendGrid Response - Body: {}", response.getBody());

            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("✅ HTML email sent successfully to {}", toEmail);
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
     * UPDATED: Test email with better HTML template
     */
    public boolean sendTestEmail(String toEmail, String subject, String body) {
        log.info("=== SENDGRID TEST EMAIL ===");
        log.info("To: {}", toEmail);
        log.info("From: {} ({})", fromEmail, fromName);

        try {
            // If no custom body provided, use a test template with buttons
            String testBody = (body != null && !body.trim().isEmpty()) ? body : createTestEmailBody();
            String testSubject = (subject != null && !subject.trim().isEmpty()) ? subject : "Test Email - HTML Buttons";

            return sendHtmlEmail(toEmail, "", testSubject, testBody, fromName);

        } catch (Exception e) {
            log.error("❌ Error sending test email: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * NEW: Create a test email with working buttons
     */
    private String createTestEmailBody() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Test Email</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #f4f4f4;">
                <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                    <div style="background-color: #2563eb; color: white; padding: 20px; text-align: center;">
                        <h1 style="margin: 0;">Test Email</h1>
                        <p style="margin: 5px 0 0 0;">HTML Button Test</p>
                    </div>
                    <div style="padding: 30px 20px;">
                        <p>This is a test email to verify HTML rendering and buttons work correctly.</p>
                        <div style="text-align: center; margin: 25px 0;">
                            <div style="margin-bottom: 15px;">
                                <a href="https://google.com" style="display: inline-block; background-color: #16a34a; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    🌟 Test Google Button
                                </a>
                            </div>
                            <div style="margin-bottom: 15px;">
                                <a href="https://facebook.com" style="display: inline-block; background-color: #1877f2; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    📘 Test Facebook Button
                                </a>
                            </div>
                            <div style="margin-bottom: 15px;">
                                <a href="https://reputul.com" style="display: inline-block; background-color: #6b7280; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                                    💬 Test Feedback Button
                                </a>
                            </div>
                        </div>
                        <p>If you can see the buttons above and they're clickable, HTML emails are working correctly!</p>
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
     * LEGACY: Keep your existing method for backward compatibility
     */
    private String processEmailTemplate(String template, Customer customer, Business business, String reviewLink) {
        if (template == null) {
            return "";
        }

        return template
                .replace("{{customerName}}", customer.getName() != null ? customer.getName() : "")
                .replace("{{businessName}}", business.getName() != null ? business.getName() : "")
                .replace("{{serviceType}}", customer.getServiceType() != null ? customer.getServiceType() : "")
                .replace("{{serviceDate}}", customer.getServiceDate() != null ? customer.getServiceDate().toString() : "")
                .replace("{{businessPhone}}", business.getPhone() != null ? business.getPhone() : "")
                .replace("{{businessWebsite}}", business.getWebsite() != null ? business.getWebsite() : "")
                .replace("{{reviewLink}}", reviewLink != null ? reviewLink : "")
                .replace("\n", "<br>"); // Convert line breaks to HTML
    }

    public String generateReviewLink(Business business) {
        // Generate unique review collection link
        return String.format("%s/business/%d/review", baseUrl, business.getId());
    }

    /**
     * NEW: Send password reset email
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
     * NEW: Create password reset email HTML template
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