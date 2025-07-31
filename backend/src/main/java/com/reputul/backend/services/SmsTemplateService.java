package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class SmsTemplateService {

    // SMS length limits
    private static final int SMS_SINGLE_LIMIT = 160;
    private static final int SMS_CONCAT_LIMIT = 1600;
    private static final int SMS_UNICODE_LIMIT = 70;

    /**
     * Generate review request SMS message for customer
     */
    public String generateReviewRequestMessage(Customer customer) {
        try {
            // Create optimized SMS template for review requests
            String template = getReviewRequestTemplate();

            // Generate variables map
            Map<String, String> variables = createSmsVariableMap(customer);

            // Render and optimize for SMS
            String message = renderTemplate(template, variables);
            return optimizeForSms(message);

        } catch (Exception e) {
            log.error("Failed to generate review request SMS for customer {}: {}", customer.getId(), e.getMessage());
            return createFallbackMessage(customer);
        }
    }

    /**
     * Generate follow-up SMS message
     */
    public String generateFollowUpMessage(Customer customer, String followUpType) {
        try {
            String template = getFollowUpTemplate(followUpType);
            Map<String, String> variables = createSmsVariableMap(customer);
            String message = renderTemplate(template, variables);
            return optimizeForSms(message);

        } catch (Exception e) {
            log.error("Failed to generate follow-up SMS for customer {}: {}", customer.getId(), e.getMessage());
            return createFallbackFollowUpMessage(customer);
        }
    }

    /**
     * SMS-optimized review request template
     */
    private String getReviewRequestTemplate() {
        return "Hi {{customerName}}! Thanks for choosing {{businessName}} for your {{serviceType}}. " +
                "Share your experience: {{reviewUrl}} " +
                "Reply STOP to opt out.";
    }

    /**
     * SMS follow-up templates
     */
    private String getFollowUpTemplate(String followUpType) {
        switch (followUpType.toLowerCase()) {
            case "3_day":
                return "Hi {{customerName}}! Hope you're happy with your {{serviceType}} from {{businessName}}. " +
                        "Quick review: {{reviewUrl}} Thanks!";
            case "7_day":
                return "Hi {{customerName}}, how was your {{serviceType}} experience with {{businessName}}? " +
                        "Your feedback helps: {{reviewUrl}}";
            case "14_day":
                return "{{customerName}}, we'd love your feedback on {{businessName}}'s {{serviceType}}. " +
                        "Share here: {{reviewUrl}} Thank you!";
            default:
                return getReviewRequestTemplate();
        }
    }

    /**
     * Create SMS-optimized variable map
     */
    private Map<String, String> createSmsVariableMap(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        Business business = customer.getBusiness();

        // Use shorter, SMS-friendly names
        variables.put("customerName", getFirstName(customer.getName()));
        variables.put("businessName", shortenBusinessName(business.getName()));
        variables.put("serviceType", shortenServiceType(customer.getServiceType()));
        variables.put("serviceDate", formatDateForSms(customer.getServiceDate().toString()));

        // Generate shortened review URL
        String reviewUrl = generateShortReviewUrl(customer);
        variables.put("reviewUrl", reviewUrl);

        // Add business contact info (optional, for longer messages)
        variables.put("businessPhone", business.getPhone() != null ? business.getPhone() : "");

        return variables;
    }

    /**
     * Render template with variables
     */
    private String renderTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * Optimize message for SMS constraints
     */
    private String optimizeForSms(String message) {
        if (message == null) {
            return "";
        }

        // Remove extra whitespace
        message = message.replaceAll("\\s+", " ").trim();

        // Check if message fits in single SMS
        if (message.length() <= SMS_SINGLE_LIMIT) {
            return message;
        }

        // If too long, try to shorten intelligently
        if (message.length() > SMS_CONCAT_LIMIT) {
            log.warn("SMS message too long ({} chars), applying aggressive shortening", message.length());
            return shortenMessage(message);
        }

        log.info("SMS message will be sent as concatenated message ({} chars)", message.length());
        return message;
    }

    /**
     * Shorten message aggressively if needed
     */
    private String shortenMessage(String message) {
        // Remove optional parts and shorten
        message = message.replaceAll("Reply STOP to opt out\\.", "");
        message = message.replaceAll("\\s+", " ").trim();

        // If still too long, truncate with ellipsis
        if (message.length() > SMS_CONCAT_LIMIT - 3) {
            return message.substring(0, SMS_CONCAT_LIMIT - 3) + "...";
        }

        return message;
    }

    /**
     * Generate shortened review URL for SMS
     */
    private String generateShortReviewUrl(Customer customer) {
        try {
            Business business = customer.getBusiness();

            // Priority 1: Use private feedback URL (shorter and more direct)
            String baseUrl = "http://localhost:3000/feedback/" + customer.getId();

            // For SMS, we can use a URL shortener in production
            // For now, return the direct URL
            return baseUrl;

        } catch (Exception e) {
            log.error("Failed to generate review URL for customer {}: {}", customer.getId(), e.getMessage());
            return "http://localhost:3000/feedback/" + customer.getId();
        }
    }

    /**
     * Get first name only for SMS brevity
     */
    private String getFirstName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "there";
        }

        String[] parts = fullName.trim().split("\\s+");
        return parts[0];
    }

    /**
     * Shorten business name for SMS
     */
    private String shortenBusinessName(String businessName) {
        if (businessName == null) {
            return "us";
        }

        // Remove common business suffixes to save space
        String shortened = businessName
                .replaceAll("\\b(LLC|Inc|Corp|Company|Services|Service|Solutions)\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Limit to reasonable length for SMS
        if (shortened.length() > 25) {
            shortened = shortened.substring(0, 22) + "...";
        }

        return shortened.isEmpty() ? businessName : shortened;
    }

    /**
     * Shorten service type for SMS
     */
    private String shortenServiceType(String serviceType) {
        if (serviceType == null) {
            return "service";
        }

        // Common service type abbreviations
        String shortened = serviceType
                .replaceAll("\\brepair\\b", "fix")
                .replaceAll("\\binstallation\\b", "install")
                .replaceAll("\\bmaintenance\\b", "maint")
                .replaceAll("\\bconsultation\\b", "consult");

        return shortened.length() > 20 ? shortened.substring(0, 17) + "..." : shortened;
    }

    /**
     * Format date for SMS (shorter format)
     */
    private String formatDateForSms(String dateString) {
        try {
            // Convert "2025-01-15" to "Jan 15" for brevity
            String[] parts = dateString.split("-");
            if (parts.length >= 3) {
                int month = Integer.parseInt(parts[1]);
                String[] months = {"", "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
                return months[month] + " " + parts[2];
            }
        } catch (Exception e) {
            log.warn("Failed to format date for SMS: {}", dateString);
        }
        return dateString;
    }

    /**
     * Create fallback message if template processing fails
     */
    private String createFallbackMessage(Customer customer) {
        String firstName = getFirstName(customer.getName());
        String businessName = customer.getBusiness().getName();
        String reviewUrl = "http://localhost:3000/feedback/" + customer.getId();

        return String.format("Hi %s! Thanks for choosing %s. Please share your experience: %s",
                firstName, businessName, reviewUrl);
    }

    /**
     * Create fallback follow-up message
     */
    private String createFallbackFollowUpMessage(Customer customer) {
        String firstName = getFirstName(customer.getName());
        String businessName = customer.getBusiness().getName();
        String reviewUrl = "http://localhost:3000/feedback/" + customer.getId();

        return String.format("Hi %s! How was your experience with %s? Share feedback: %s",
                firstName, businessName, reviewUrl);
    }

    /**
     * Validate SMS message length and provide recommendations
     */
    public SmsValidationResult validateMessage(String message) {
        if (message == null) {
            return new SmsValidationResult(false, "Message cannot be null", 0, 0);
        }

        int length = message.length();
        int estimatedSms = calculateSmsCount(message);

        if (length <= SMS_SINGLE_LIMIT) {
            return new SmsValidationResult(true, "Perfect! Fits in single SMS", length, 1);
        } else if (length <= SMS_CONCAT_LIMIT) {
            return new SmsValidationResult(true,
                    String.format("Will be sent as %d concatenated messages", estimatedSms),
                    length, estimatedSms);
        } else {
            return new SmsValidationResult(false,
                    "Message too long. Consider shortening.",
                    length, estimatedSms);
        }
    }

    /**
     * Calculate number of SMS messages needed
     */
    private int calculateSmsCount(String message) {
        if (message.length() <= SMS_SINGLE_LIMIT) {
            return 1;
        }
        // Concatenated SMS has 153 chars per segment (7 chars used for headers)
        return (int) Math.ceil((double) message.length() / 153);
    }

    /**
     * SMS validation result
     */
    public static class SmsValidationResult {
        private final boolean valid;
        private final String message;
        private final int characterCount;
        private final int smsCount;

        public SmsValidationResult(boolean valid, String message, int characterCount, int smsCount) {
            this.valid = valid;
            this.message = message;
            this.characterCount = characterCount;
            this.smsCount = smsCount;
        }

        // Getters
        public boolean isValid() { return valid; }
        public String getMessage() { return message; }
        public int getCharacterCount() { return characterCount; }
        public int getSmsCount() { return smsCount; }
    }
}