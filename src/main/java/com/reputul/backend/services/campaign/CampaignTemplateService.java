package com.reputul.backend.services.campaign;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignTemplateService {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    /**
     * Build template variables for a review request
     */
    public Map<String, Object> buildTemplateVariables(ReviewRequest reviewRequest) {
        Map<String, Object> variables = new HashMap<>();

        try {
            // Get business information directly from the relationship
            Business business = reviewRequest.getBusiness();

            // Get customer information directly from the relationship
            Customer customer = reviewRequest.getCustomer();

            // Basic customer variables
            variables.put("customerName", customer.getName() != null ? customer.getName() : "Valued Customer");
            variables.put("customerFirstName", extractFirstName(customer.getName()));
            variables.put("customerEmail", customer.getEmail());
            variables.put("customerPhone", formatPhoneNumber(customer.getPhone()));

            // Business variables
            variables.put("businessName", business.getName());
            variables.put("businessPhone", formatPhoneNumber(business.getPhone()));
            variables.put("businessWebsite", business.getWebsite());
            variables.put("businessAddress", formatAddress(business));

            // Service variables - get from customer, not review request
            variables.put("serviceType", customer.getServiceType() != null ? customer.getServiceType() : "service");
            variables.put("serviceDate", customer.getServiceDate() != null ?
                    customer.getServiceDate().format(DATE_FORMATTER) : "recently");

            // Review link - this will be generated based on your review request logic
            variables.put("reviewLink", generateReviewLink(reviewRequest));

            // Feedback link for private feedback
            variables.put("feedbackLink", generateFeedbackLink(reviewRequest));

            // Date variables
            variables.put("currentDate", java.time.LocalDate.now().format(DATE_FORMATTER));
            variables.put("currentYear", String.valueOf(java.time.Year.now().getValue()));

            log.debug("Built template variables for review request {}: {}", reviewRequest.getId(), variables.keySet());

        } catch (Exception e) {
            log.error("Error building template variables for review request {}: {}", reviewRequest.getId(), e.getMessage(), e);

            // Fallback variables to prevent template errors
            variables.put("customerName", "Valued Customer");
            variables.put("customerFirstName", "Customer");
            variables.put("businessName", "Our Business");
            variables.put("serviceType", "service");
            variables.put("reviewLink", "#");
            variables.put("feedbackLink", "#");
        }

        return variables;
    }

    /**
     * Process a template string by replacing variables
     */
    public String processTemplate(String template, Map<String, Object> variables) {
        if (template == null || template.trim().isEmpty()) {
            return template;
        }

        StringBuffer result = new StringBuffer();
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);

        while (matcher.find()) {
            String variableName = matcher.group(1).trim();
            Object value = variables.get(variableName);

            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
            } else {
                // Leave unknown variables as-is or replace with placeholder
                log.warn("Unknown template variable: {}", variableName);
                matcher.appendReplacement(result, "{{" + variableName + "}}");
            }
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Validate a template by checking for required variables
     */
    public boolean validateTemplate(String template, String... requiredVariables) {
        if (template == null) {
            return false;
        }

        for (String required : requiredVariables) {
            if (!template.contains("{{" + required + "}}")) {
                log.warn("Template missing required variable: {}", required);
                return false;
            }
        }

        return true;
    }

    /**
     * Extract all variables from a template
     */
    public java.util.Set<String> extractVariables(String template) {
        java.util.Set<String> variables = new java.util.HashSet<>();

        if (template != null) {
            Matcher matcher = TEMPLATE_PATTERN.matcher(template);
            while (matcher.find()) {
                variables.add(matcher.group(1).trim());
            }
        }

        return variables;
    }

    /**
     * Get available template variables
     */
    public Map<String, String> getAvailableVariables() {
        Map<String, String> variables = new HashMap<>();

        // Customer variables
        variables.put("customerName", "Customer's full name");
        variables.put("customerFirstName", "Customer's first name");
        variables.put("customerEmail", "Customer's email address");
        variables.put("customerPhone", "Customer's phone number");

        // Business variables
        variables.put("businessName", "Business name");
        variables.put("businessPhone", "Business phone number");
        variables.put("businessWebsite", "Business website URL");
        variables.put("businessAddress", "Business address");

        // Service variables
        variables.put("serviceType", "Type of service provided");
        variables.put("serviceDate", "Date service was completed");

        // Link variables
        variables.put("reviewLink", "Link to leave a public review");
        variables.put("feedbackLink", "Link to private feedback form");

        // Date variables
        variables.put("currentDate", "Current date");
        variables.put("currentYear", "Current year");

        return variables;
    }

    // Private helper methods

    private String extractFirstName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "Customer";
        }

        String[] parts = fullName.trim().split("\\s+");
        return parts[0];
    }

    private String formatPhoneNumber(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return "";
        }

        // Remove all non-digits
        String digitsOnly = phone.replaceAll("[^0-9]", "");

        // Format as (XXX) XXX-XXXX if it's a 10-digit US number
        if (digitsOnly.length() == 10) {
            return String.format("(%s) %s-%s",
                    digitsOnly.substring(0, 3),
                    digitsOnly.substring(3, 6),
                    digitsOnly.substring(6));
        }

        return phone; // Return as-is if not a standard format
    }

    private String formatAddress(Business business) {
        StringBuilder address = new StringBuilder();

        if (business.getAddress() != null && !business.getAddress().trim().isEmpty()) {
            address.append(business.getAddress());
        }

        // Add city, state if available (you may need to add these fields to Business entity)
        // For now, just return the address field
        return address.toString();
    }

    private String generateReviewLink(ReviewRequest reviewRequest) {
        // This should generate the appropriate review link based on your feedback gate logic
        // For now, return a placeholder - you'll implement this based on your existing review link generation

        String baseUrl = "https://app.reputul.com"; // TODO: Make this configurable
        return String.format("%s/feedback-gate/%s", baseUrl, reviewRequest.getCustomer().getId());
    }

    private String generateFeedbackLink(ReviewRequest reviewRequest) {
        // Generate private feedback link
        String baseUrl = "https://app.reputul.com"; // TODO: Make this configurable
        return String.format("%s/feedback/%s", baseUrl, reviewRequest.getCustomer().getId());
    }
}