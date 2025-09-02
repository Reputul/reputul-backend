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
import java.util.Set;
import java.util.regex.Pattern;

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

    @Value("${app.base.url:http://localhost:3000}")
    private String baseUrl;

    // Compliance validation patterns
    private static final Pattern STOP_KEYWORD_PATTERN = Pattern.compile("\\b(STOP|HELP)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATES_DISCLOSURE_PATTERN = Pattern.compile("(msg|message).*(rate|fee|charge)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> REQUIRED_DISCLOSURES = Set.of("STOP", "HELP", "rates");

    /**
     * UPDATED: Generate compliance-aware review request SMS message per Twilio requirements
     * Format: {BusinessName}: We'd love your feedback—review your {service}: {branded_link}. Reply HELP for help, STOP to opt out. Msg & data rates may apply.
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
     * UPDATED: COMPLIANCE-READY SMS review request template with all required disclosures
     * Format per Twilio requirements: Business name + HELP + STOP + rates disclosure
     */
    private String getComplianceReviewRequestTemplate() {
        return "{{businessName}}: We'd love your feedback—please review your {{serviceType}}: {{reviewUrl}}. " +
                "Reply HELP for help, STOP to opt out. Msg & data rates may apply.";
    }

    /**
     * UPDATED: COMPLIANCE-READY follow-up templates with required disclosures
     */
    private String getComplianceFollowUpTemplate(String followUpType) {
        switch (followUpType.toLowerCase()) {
            case "3_day":
                return "{{businessName}}: Hi {{customerName}}! Hope your {{serviceType}} went well. " +
                        "Quick review: {{reviewUrl}}. Reply HELP for help, STOP to opt out. Msg & data rates may apply.";

            case "7_day":
                return "{{businessName}}: Thanks for choosing us for your {{serviceType}}! " +
                        "Share your experience: {{reviewUrl}}. Reply HELP for help, STOP to opt out. Msg & data rates may apply.";

            case "reminder":
                return "{{businessName}}: Just a friendly reminder to review your {{serviceType}}: {{reviewUrl}}. " +
                        "Reply HELP for help, STOP to opt out. Msg & data rates may apply.";

            default:
                return "{{businessName}}: Thanks for your business! Please review your experience: {{reviewUrl}}. " +
                        "Reply HELP for help, STOP to opt out. Msg & data rates may apply.";
        }
    }

    /**
     * Create variable map for SMS template rendering
     */
    private Map<String, String> createSmsVariableMap(Customer customer) {
        Business business = customer.getBusiness();
        Map<String, String> variables = new HashMap<>();

        // Basic customer info
        variables.put("customerName", customer.getName().split(" ")[0]); // First name only for SMS
        variables.put("fullCustomerName", customer.getName());

        // Business info - use actual business name, not the platform name
        variables.put("businessName", business.getName());
        variables.put("serviceType", customer.getServiceType() != null ? customer.getServiceType() : "service");

        // Contact info
        variables.put("supportEmail", supportEmail);

        // Only include supportPhone if it's not empty
        if (supportPhone != null && !supportPhone.trim().isEmpty()) {
            variables.put("supportPhone", supportPhone);
        }

        // Generate branded review URL (no public shorteners per Twilio guidelines)
        String reviewUrl = generateBrandedReviewUrl(customer);
        variables.put("reviewUrl", reviewUrl);

        return variables;
    }

    /**
     * UPDATED: Generate branded review URL (no public shorteners per Twilio compliance)
     */
    private String generateBrandedReviewUrl(Customer customer) {
        // Use reputul.com domain instead of public shorteners like bit.ly
        return String.format("%s/review/%s", baseUrl, customer.getId());
    }

    /**
     * Render SMS template with variables
     */
    private String renderTemplate(String template, Map<String, String> variables) {
        String result = template;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            result = result.replace(placeholder, entry.getValue());
        }

        return result;
    }

    /**
     * Optimize message for SMS length and readability
     */
    private String optimizeForSms(String message) {
        if (message == null) return "";

        // Remove excessive whitespace
        message = message.replaceAll("\\s+", " ").trim();

        // If message is too long, try to shorten while keeping compliance elements
        if (message.length() > SMS_SINGLE_LIMIT) {
            log.warn("SMS message exceeds single SMS limit ({} chars): {}", message.length(), message);

            // Try to shorten without losing compliance elements
            if (message.length() > SMS_CONCAT_LIMIT) {
                log.error("SMS message exceeds concatenation limit ({} chars), truncating", message.length());
                message = message.substring(0, SMS_CONCAT_LIMIT - 3) + "...";
            }
        }

        return message;
    }

    /**
     * UPDATED: Create compliant fallback message if template rendering fails
     */
    private String createComplianceFallbackMessage(Customer customer) {
        Business business = customer.getBusiness();
        String reviewUrl = generateBrandedReviewUrl(customer);

        return String.format("%s: Please review your service: %s. Reply HELP for help, STOP to opt out. Msg & data rates may apply.",
                business.getName(), reviewUrl);
    }

    /**
     * Create compliant fallback follow-up message
     */
    private String createComplianceFollowUpFallbackMessage(Customer customer) {
        Business business = customer.getBusiness();
        String reviewUrl = generateBrandedReviewUrl(customer);

        return String.format("%s: Thanks for your business! Please share your feedback: %s. Reply HELP for help, STOP to opt out. Msg & data rates may apply.",
                business.getName(), reviewUrl);
    }

    /**
     * NEW: Validate SMS message compliance per Twilio requirements
     */
    public SmsComplianceValidationResult validateCompliance(String message) {
        if (message == null || message.trim().isEmpty()) {
            return SmsComplianceValidationResult.failure("Message is empty");
        }

        SmsComplianceValidationResult result = new SmsComplianceValidationResult();

        // Check for required STOP keyword
        if (!STOP_KEYWORD_PATTERN.matcher(message).find()) {
            result.addIssue("Missing required STOP or HELP keyword");
        }

        // Check for rates disclosure
        if (!RATES_DISCLOSURE_PATTERN.matcher(message).find() &&
                !message.toLowerCase().contains("rates apply") &&
                !message.toLowerCase().contains("msg rates") &&
                !message.toLowerCase().contains("data rates")) {
            result.addIssue("Missing required rates disclosure (e.g., 'Msg & data rates may apply')");
        }

        // Check for business identification
        if (!message.contains(":") && !message.toLowerCase().contains(businessName.toLowerCase())) {
            result.addIssue("Message should clearly identify the business sender");
        }

        // Length validation
        if (message.length() > SMS_CONCAT_LIMIT) {
            result.addIssue("Message exceeds maximum SMS length (" + SMS_CONCAT_LIMIT + " chars)");
        }

        return result;
    }

    /**
     * Compliance validation result
     */
    public static class SmsComplianceValidationResult {
        private boolean compliant = true;
        private final StringBuilder issues = new StringBuilder();

        public void addIssue(String issue) {
            this.compliant = false;
            if (issues.length() > 0) {
                issues.append("; ");
            }
            issues.append(issue);
        }

        public boolean isCompliant() {
            return compliant;
        }

        public String getIssues() {
            return issues.toString();
        }

        public static SmsComplianceValidationResult failure(String issue) {
            SmsComplianceValidationResult result = new SmsComplianceValidationResult();
            result.addIssue(issue);
            return result;
        }

        public static SmsComplianceValidationResult success() {
            return new SmsComplianceValidationResult();
        }
    }
}