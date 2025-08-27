package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.business.name:Reputul}")
    private String businessName;

    @Value("${app.support.phone:1-800-REPUTUL}")
    private String supportPhone;

    @Value("${app.support.email:support@reputul.com}")
    private String supportEmail;

    /**
     * Generate compliance-aware review request SMS message
     */
    public String generateReviewRequestMessage(Customer customer) {
        try {
            String template = getComplianceReviewRequestTemplate();
            Map<String, String> variables = createSmsVariableMap(customer);
            String message = renderTemplate(template, variables);
            return optimizeForSms(message);

        } catch (Exception e) {
            log.error("Failed to generate review request SMS for customer {}: {}", customer.getId(), e.getMessage());
            return createComplianceFallbackMessage(customer);
        }
    }

    /**
     * Generate compliance-aware follow-up SMS message
     */
    public String generateFollowUpMessage(Customer customer, String followUpType) {
        try {
            String template = getComplianceFollowUpTemplate(followUpType);
            Map<String, String> variables = createSmsVariableMap(customer);
            String message = renderTemplate(template, variables);
            return optimizeForSms(message);

        } catch (Exception e) {
            log.error("Failed to generate follow-up SMS for customer {}: {}", customer.getId(), e.getMessage());
            return createComplianceFollowUpFallbackMessage(customer);
        }
    }

    /**
     * COMPLIANCE: SMS-optimized review request template with required disclosures
     */
    private String getComplianceReviewRequestTemplate() {
        return "{{businessName}}: Hi {{customerName}}! Thanks for your {{serviceType}}. " +
                "Share your experience: {{reviewUrl}} " +
                "Reply STOP to opt out. Msg rates apply. {{supportContact}}";
    }

    /**
     * COMPLIANCE: SMS follow-up templates with required disclosures
     */
    private String getComplianceFollowUpTemplate(String followUpType) {
        switch (followUpType.toLowerCase()) {
            case "3_day":
                return "{{businessName}}: Hi {{customerName}}! Hope your {{serviceType}} went well. " +
                        "Quick review: {{reviewUrl}} Reply STOP to opt out. {{supportContact}}";
            case "7_day":
                return "{{businessName}}: How was your {{serviceType}} experience, {{customerName}}? " +
                        "Share feedback: {{reviewUrl}} Reply STOP to opt out. {{supportContact}}";
            case "14_day":
                return "{{businessName}}: {{customerName}}, your {{serviceType}} feedback helps others. " +
                        "Review: {{reviewUrl}} Reply STOP to opt out. {{supportContact}}";
            default:
                return getComplianceReviewRequestTemplate();
        }
    }

    /**
     * Create SMS-optimized variable map with compliance elements
     */
    private Map<String, String> createSmsVariableMap(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        Business business = customer.getBusiness();

        // Core customer info
        variables.put("customerName", getFirstName(customer.getName()));
        variables.put("businessName", shortenBusinessName(business.getName()));
        variables.put("serviceType", shortenServiceType(customer.getServiceType()));
        variables.put("serviceDate", formatDateForSms(customer.getServiceDate().toString()));

        // Review URL
        String reviewUrl = generateShortReviewUrl(customer);
        variables.put("reviewUrl", reviewUrl);

        // COMPLIANCE: Support contact info
        variables.put("supportContact", formatSupportContact(business));

        // Business contact info (optional)
        variables.put("businessPhone", business.getPhone() != null ? business.getPhone() : "");
        variables.put("businessWebsite", business.getWebsite() != null ? business.getWebsite() : "");

        return variables;
    }

    /**
     * COMPLIANCE: Format support contact for SMS footer
     */
    private String formatSupportContact(Business business) {
        // Use business phone if available, otherwise use support phone
        String contactPhone = business.getPhone() != null ? business.getPhone() : supportPhone;
        return "Help: " + contactPhone;
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
     * COMPLIANCE: Optimize message for SMS constraints with mandatory disclosures
     */
    private String optimizeForSms(String message) {
        if (message == null) {
            return "";
        }

        // Remove extra whitespace
        message = message.replaceAll("\\s+", " ").trim();

        // Ensure compliance elements are preserved
        if (!message.contains("STOP")) {
            message = addStopDisclosure(message);
        }

        // Check if message fits in single SMS
        if (message.length() <= SMS_SINGLE_LIMIT) {
            return message;
        }

        // If too long for concatenated SMS, intelligently shorten
        if (message.length() > SMS_CONCAT_LIMIT) {
            log.warn("SMS message too long ({} chars), applying compliance-aware shortening", message.length());
            return shortenComplianceMessage(message);
        }

        log.info("SMS message will be sent as concatenated message ({} chars)", message.length());
        return message;
    }

    /**
     * COMPLIANCE: Add STOP disclosure if missing
     */
    private String addStopDisclosure(String message) {
        if (message.length() + 20 <= SMS_SINGLE_LIMIT) {
            return message + " Reply STOP to opt out.";
        }
        return message; // Can't fit, but other compliance checks will handle
    }

    /**
     * COMPLIANCE: Shorten message while preserving mandatory compliance elements
     */
    private String shortenComplianceMessage(String message) {
        // Extract and preserve compliance elements
        String stopText = "Reply STOP to opt out";
        String helpText = extractHelpContact(message);

        // Calculate space available after compliance elements
        int complianceLength = stopText.length() + (helpText != null ? helpText.length() + 1 : 0);
        int availableLength = SMS_CONCAT_LIMIT - complianceLength - 10; // Buffer for periods and spaces

        if (availableLength < 50) {
            // Minimal message if very constrained
            return createMinimalComplianceMessage(message);
        }

        // Shorten the main content while keeping compliance
        String mainContent = message;
        mainContent = mainContent.replaceAll("Reply STOP.*", "").trim();

        if (helpText != null) {
            mainContent = mainContent.replace(helpText, "").trim();
        }

        if (mainContent.length() > availableLength) {
            mainContent = mainContent.substring(0, availableLength - 3) + "...";
        }

        // Reassemble with compliance elements
        StringBuilder result = new StringBuilder(mainContent);
        result.append(" ").append(stopText).append(".");

        if (helpText != null) {
            result.append(" ").append(helpText);
        }

        return result.toString();
    }

    /**
     * Extract help contact from message
     */
    private String extractHelpContact(String message) {
        if (message.contains("Help:")) {
            int helpIndex = message.indexOf("Help:");
            int endIndex = message.length();
            // Find end of help contact (usually end of message or before another sentence)
            for (int i = helpIndex; i < message.length(); i++) {
                if (message.charAt(i) == '.' && i + 1 < message.length() &&
                        Character.isUpperCase(message.charAt(i + 1))) {
                    endIndex = i + 1;
                    break;
                }
            }
            return message.substring(helpIndex, endIndex).trim();
        }
        return null;
    }

    /**
     * Create minimal compliance message when severely space-constrained
     */
    private String createMinimalComplianceMessage(String originalMessage) {
        // Extract business name and basic info
        String businessName = extractBusinessName(originalMessage);
        return businessName + " review: [link] Reply STOP to opt out. Help: " + supportPhone;
    }

    /**
     * Extract business name from original message
     */
    private String extractBusinessName(String message) {
        // Try to extract business name from the beginning of the message
        if (message.contains(":")) {
            String beforeColon = message.substring(0, message.indexOf(":")).trim();
            if (beforeColon.length() < 30) { // Reasonable business name length
                return beforeColon;
            }
        }
        return businessName; // Fallback to configured business name
    }

    /**
     * Generate shortened review URL for SMS
     */
    private String generateShortReviewUrl(Customer customer) {
        try {
            // Use customer-specific feedback URL
            String baseUrl = "http://localhost:3000/feedback/" + customer.getId();

            // In production, consider using a URL shortener service
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
        String firstName = parts[0];

        // Truncate very long first names
        if (firstName.length() > 12) {
            firstName = firstName.substring(0, 12);
        }

        return firstName;
    }

    /**
     * Shorten business name for SMS with compliance considerations
     */
    private String shortenBusinessName(String businessName) {
        if (businessName == null) {
            return this.businessName; // Use configured business name
        }

        // Remove common business suffixes to save space
        String shortened = businessName
                .replaceAll("\\b(LLC|Inc|Corp|Company|Services|Service|Solutions|Ltd)\\b", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Limit to reasonable length for SMS sender identification
        if (shortened.length() > 20) {
            shortened = shortened.substring(0, 17) + "...";
        }

        return shortened.isEmpty() ? this.businessName : shortened;
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
                .replaceAll("\\bconsultation\\b", "consult")
                .replaceAll("\\bappointment\\b", "appt");

        return shortened.length() > 25 ? shortened.substring(0, 22) + "..." : shortened;
    }

    /**
     * Format date for SMS (shorter format)
     */
    private String formatDateForSms(String dateString) {
        try {
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
     * COMPLIANCE: Create fallback message with all required disclosures
     */
    private String createComplianceFallbackMessage(Customer customer) {
        String firstName = getFirstName(customer.getName());
        String businessName = customer.getBusiness().getName();
        String reviewUrl = "http://localhost:3000/feedback/" + customer.getId();

        return String.format("%s: Hi %s! Please review your experience: %s Reply STOP to opt out. Help: %s",
                shortenBusinessName(businessName), firstName, reviewUrl, supportPhone);
    }

    /**
     * COMPLIANCE: Create fallback follow-up message with required disclosures
     */
    private String createComplianceFollowUpFallbackMessage(Customer customer) {
        String firstName = getFirstName(customer.getName());
        String businessName = customer.getBusiness().getName();
        String reviewUrl = "http://localhost:3000/feedback/" + customer.getId();

        return String.format("%s: Hi %s! Share your feedback: %s Reply STOP to opt out. Help: %s",
                shortenBusinessName(businessName), firstName, reviewUrl, supportPhone);
    }

    /**
     * COMPLIANCE: Validate SMS message for compliance requirements
     */
    public SmsComplianceValidationResult validateCompliance(String message) {
        if (message == null || message.trim().isEmpty()) {
            return new SmsComplianceValidationResult(false, "Message cannot be empty", null);
        }

        StringBuilder issues = new StringBuilder();

        // Check for required STOP disclosure
        if (!message.toUpperCase().contains("STOP")) {
            issues.append("Missing STOP disclosure; ");
        }

        // Check for business identification
        if (!containsBusinessIdentification(message)) {
            issues.append("Missing business identification; ");
        }

        // Check message length
        if (message.length() > SMS_CONCAT_LIMIT) {
            issues.append("Message exceeds SMS length limit; ");
        }

        // Check for support contact
        if (!containsSupportContact(message)) {
            issues.append("Missing support contact information; ");
        }

        boolean isCompliant = issues.length() == 0;
        String issuesList = issues.length() > 0 ? issues.toString().replaceAll("; $", "") : null;

        return new SmsComplianceValidationResult(isCompliant, issuesList, calculateSmsCount(message));
    }

    private boolean containsBusinessIdentification(String message) {
        return message.contains(businessName) || message.contains(":");
    }

    private boolean containsSupportContact(String message) {
        return message.contains("Help:") || message.contains(supportPhone) ||
                message.matches(".*\\b\\d{3}-\\d{3}-\\d{4}\\b.*");
    }

    /**
     * Calculate number of SMS messages needed
     */
    private int calculateSmsCount(String message) {
        if (message.length() <= SMS_SINGLE_LIMIT) {
            return 1;
        }
        return (int) Math.ceil((double) message.length() / 153);
    }

    /**
     * SMS compliance validation result
     */
    public static class SmsComplianceValidationResult {
        private final boolean compliant;
        private final String issues;
        private final Integer smsCount;

        public SmsComplianceValidationResult(boolean compliant, String issues, Integer smsCount) {
            this.compliant = compliant;
            this.issues = issues;
            this.smsCount = smsCount;
        }

        public boolean isCompliant() { return compliant; }
        public String getIssues() { return issues; }
        public Integer getSmsCount() { return smsCount; }
    }
}