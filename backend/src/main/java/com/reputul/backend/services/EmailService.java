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
import java.util.Map;

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
        this.sendGrid = new SendGrid(apiKey);
    }

    public boolean sendReviewRequest(Customer customer, Business business, String subject, String body, String reviewLink) {
        try {
            Email from = new Email(fromEmail, business.getName());
            Email to = new Email(customer.getEmail(), customer.getName());

            // Replace variables in the email body
            String processedBody = processEmailTemplate(body, customer, business, reviewLink);
            String processedSubject = processEmailTemplate(subject, customer, business, reviewLink);

            Content content = new Content("text/html", processedBody);
            Mail mail = new Mail(from, processedSubject, to, content);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);

            log.info("Email sent to {} - Status: {}", customer.getEmail(), response.getStatusCode());

            return response.getStatusCode() >= 200 && response.getStatusCode() < 300;

        } catch (IOException e) {
            log.error("Failed to send email to {}: {}", customer.getEmail(), e.getMessage());
            return false;
        }
    }

    public boolean sendTestEmail(String toEmail, String subject, String body) {
        try {
            Email from = new Email(fromEmail, fromName);
            Email to = new Email(toEmail);

            Content content = new Content("text/html", body);
            Mail mail = new Mail(from, subject, to, content);

            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);

            log.info("Test email sent to {} - Status: {}", toEmail, response.getStatusCode());

            return response.getStatusCode() >= 200 && response.getStatusCode() < 300;

        } catch (IOException e) {
            log.error("Failed to send test email to {}: {}", toEmail, e.getMessage());
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