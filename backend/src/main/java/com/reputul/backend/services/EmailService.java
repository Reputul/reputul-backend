package com.reputul.backend.services;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Service
@Slf4j
public class EmailService {

    private final SendGrid sendGrid;

    @Value("${sendgrid.from.email}")
    private String fromEmail;

    @Value("${sendgrid.from.name}")
    private String fromName;

    @Value("${app.base.url}")
    private String baseUrl;

    public EmailService(@Value("${sendgrid.api.key}") String apiKey) {
        log.info("Initializing EmailService with API key: {}...",
                apiKey != null ? apiKey.substring(0, Math.min(10, apiKey.length())) : "null");
        this.sendGrid = new SendGrid(apiKey);
    }

    public boolean sendReviewRequest(Customer customer, Business business, String subject, String body, String reviewLink) {
        log.info("Attempting to send review request to: {}", customer.getEmail());
        log.info("From email: {}, From name: {}", fromEmail, business.getName());

        try {
            Email from = new Email(fromEmail, business.getName());
            Email to = new Email(customer.getEmail(), customer.getName());

            // Replace variables in the email body
            String processedBody = processEmailTemplate(body, customer, business, reviewLink);
            String processedSubject = processEmailTemplate(subject, customer, business, reviewLink);

            log.info("Processed subject: {}", processedSubject);
            log.info("Processed body preview: {}...",
                    processedBody.length() > 100 ? processedBody.substring(0, 100) : processedBody);

            Content content = new Content("text/html", processedBody);
            Mail mail = new Mail(from, processedSubject, to, content);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            log.info("Sending request to SendGrid...");
            Response response = sendGrid.api(request);

            log.info("SendGrid Response - Status: {}", response.getStatusCode());
            log.info("SendGrid Response - Body: {}", response.getBody());
            log.info("SendGrid Response - Headers: {}", response.getHeaders());

            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("✅ Email sent successfully to {}", customer.getEmail());
            } else {
                log.error("❌ SendGrid failed with status {}: {}", response.getStatusCode(), response.getBody());
            }

            return success;

        } catch (IOException e) {
            log.error("❌ IOException sending email to {}: {}", customer.getEmail(), e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("❌ Unexpected error sending email to {}: {}", customer.getEmail(), e.getMessage(), e);
            return false;
        }
    }

    public boolean sendTestEmail(String toEmail, String subject, String body) {
        log.info("=== SENDGRID TEST EMAIL ===");
        log.info("To: {}", toEmail);
        log.info("From: {} ({})", fromEmail, fromName);
        log.info("Subject: {}", subject);
        log.info("Body: {}", body);

        try {
            Email from = new Email(fromEmail, fromName);
            Email to = new Email(toEmail);

            Content content = new Content("text/html", body);
            Mail mail = new Mail(from, subject, to, content);

            log.info("Mail object created: {}", mail.build());

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            log.info("Making SendGrid API call...");
            Response response = sendGrid.api(request);

            log.info("SendGrid Test Response - Status: {}", response.getStatusCode());
            log.info("SendGrid Test Response - Body: {}", response.getBody());
            log.info("SendGrid Test Response - Headers: {}", response.getHeaders());

            boolean success = response.getStatusCode() >= 200 && response.getStatusCode() < 300;

            if (success) {
                log.info("✅ Test email sent successfully to {}", toEmail);
            } else {
                log.error("❌ Test email failed with status {}: {}", response.getStatusCode(), response.getBody());
            }

            return success;

        } catch (IOException e) {
            log.error("❌ IOException sending test email: {}", e.getMessage(), e);
            return false;
        } catch (Exception e) {
            log.error("❌ Unexpected error sending test email: {}", e.getMessage(), e);
            return false;
        }
    }

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
}