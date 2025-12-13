package com.reputul.backend.services.campaign;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.User;
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
     * UPDATED: Now supports both camelCase (legacy) and snake_case (new preset campaigns)
     */
    public Map<String, Object> buildTemplateVariables(ReviewRequest reviewRequest) {
        Map<String, Object> variables = new HashMap<>();

        try {
            // Get business information directly from the relationship
            Business business = reviewRequest.getBusiness();

            // Get customer information directly from the relationship
            Customer customer = reviewRequest.getCustomer();

            // Get business owner (user)
            User businessOwner = business.getUser();
            String ownerName = businessOwner != null ? businessOwner.getName() : business.getName();

            // Extract first name from customer name
            String fullName = customer.getName() != null ? customer.getName() : "Valued Customer";
            String firstName = extractFirstName(fullName);

            // ================================================================
            // CUSTOMER VARIABLES (both camelCase and snake_case)
            // ================================================================

            // Legacy camelCase (for backward compatibility)
            variables.put("customerName", fullName);
            variables.put("customerFirstName", firstName);
            variables.put("customerEmail", customer.getEmail());
            variables.put("customerPhone", formatPhoneNumber(customer.getPhone()));

            // NEW: snake_case (for preset campaigns)
            variables.put("customer_name", fullName);
            variables.put("customer_first_name", firstName);
            variables.put("customer_email", customer.getEmail());
            variables.put("customer_phone", formatPhoneNumber(customer.getPhone()));

            // ================================================================
            // BUSINESS VARIABLES (both camelCase and snake_case)
            // ================================================================

            // Legacy camelCase
            variables.put("businessName", business.getName());
            variables.put("businessPhone", formatPhoneNumber(business.getPhone()));
            variables.put("businessWebsite", business.getWebsite());
            variables.put("businessAddress", formatAddress(business));

            // NEW: snake_case
            variables.put("business_name", business.getName());
            variables.put("business_owner", ownerName);
            variables.put("business_phone", formatPhoneNumber(business.getPhone()));
            variables.put("business_website", business.getWebsite());
            variables.put("business_address", formatAddress(business));
            variables.put("business_industry", business.getIndustry() != null ? business.getIndustry() : "service");

            // ================================================================
            // SERVICE VARIABLES (both camelCase and snake_case)
            // ================================================================

            String serviceType = customer.getServiceType() != null ? customer.getServiceType() : "service";
            String serviceDate = customer.getServiceDate() != null ?
                    customer.getServiceDate().format(DATE_FORMATTER) : "recently";

            // Legacy camelCase
            variables.put("serviceType", serviceType);
            variables.put("serviceDate", serviceDate);

            // NEW: snake_case
            variables.put("service_type", serviceType);
            variables.put("service_date", serviceDate);

            // ================================================================
            // LINK VARIABLES (both camelCase and snake_case)
            // ================================================================

            String reviewLink = generateReviewLink(reviewRequest);
            String feedbackLink = generateFeedbackLink(reviewRequest);
            String referralLink = generateReferralLink(reviewRequest);

            // Legacy camelCase
            variables.put("reviewLink", reviewLink);
            variables.put("feedbackLink", feedbackLink);

            // NEW: snake_case
            variables.put("review_link", reviewLink);
            variables.put("feedback_link", feedbackLink);
            variables.put("referral_link", referralLink);

            // ================================================================
            // DATE VARIABLES (both camelCase and snake_case)
            // ================================================================

            String currentDate = java.time.LocalDate.now().format(DATE_FORMATTER);
            String currentYear = String.valueOf(java.time.Year.now().getValue());

            // Legacy camelCase
            variables.put("currentDate", currentDate);
            variables.put("currentYear", currentYear);

            // NEW: snake_case
            variables.put("current_date", currentDate);
            variables.put("current_year", currentYear);

            log.debug("Built template variables for review request {} (supports both camelCase and snake_case)",
                    reviewRequest.getId());

        } catch (Exception e) {
            log.error("Error building template variables for review request {}: {}",
                    reviewRequest.getId(), e.getMessage(), e);

            // Fallback variables to prevent template errors (both formats)
            variables.put("customerName", "Valued Customer");
            variables.put("customer_name", "Valued Customer");
            variables.put("customerFirstName", "Customer");
            variables.put("customer_first_name", "Customer");
            variables.put("businessName", "Our Business");
            variables.put("business_name", "Our Business");
            variables.put("business_owner", "Owner");
            variables.put("serviceType", "service");
            variables.put("service_type", "service");
            variables.put("reviewLink", "#");
            variables.put("review_link", "#");
            variables.put("feedbackLink", "#");
            variables.put("feedback_link", "#");
            variables.put("referral_link", "#");
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
     * UPDATED: Now includes both camelCase and snake_case formats
     */
    public Map<String, String> getAvailableVariables() {
        Map<String, String> variables = new HashMap<>();

        // ================================================================
        // CUSTOMER VARIABLES
        // ================================================================
        variables.put("customer_name", "Customer's full name (NEW: snake_case)");
        variables.put("customerName", "Customer's full name (LEGACY: camelCase)");
        variables.put("customer_first_name", "Customer's first name (NEW: snake_case)");
        variables.put("customerFirstName", "Customer's first name (LEGACY: camelCase)");
        variables.put("customer_email", "Customer's email address (NEW: snake_case)");
        variables.put("customerEmail", "Customer's email address (LEGACY: camelCase)");
        variables.put("customer_phone", "Customer's phone number (NEW: snake_case)");
        variables.put("customerPhone", "Customer's phone number (LEGACY: camelCase)");

        // ================================================================
        // BUSINESS VARIABLES
        // ================================================================
        variables.put("business_name", "Business name (NEW: snake_case)");
        variables.put("businessName", "Business name (LEGACY: camelCase)");
        variables.put("business_owner", "Business owner/contact name (NEW: snake_case)");
        variables.put("business_phone", "Business phone number (NEW: snake_case)");
        variables.put("businessPhone", "Business phone number (LEGACY: camelCase)");
        variables.put("business_website", "Business website URL (NEW: snake_case)");
        variables.put("businessWebsite", "Business website URL (LEGACY: camelCase)");
        variables.put("business_address", "Business address (NEW: snake_case)");
        variables.put("businessAddress", "Business address (LEGACY: camelCase)");
        variables.put("business_industry", "Business industry/type (NEW: snake_case)");

        // ================================================================
        // SERVICE VARIABLES
        // ================================================================
        variables.put("service_type", "Type of service provided (NEW: snake_case)");
        variables.put("serviceType", "Type of service provided (LEGACY: camelCase)");
        variables.put("service_date", "Date service was completed (NEW: snake_case)");
        variables.put("serviceDate", "Date service was completed (LEGACY: camelCase)");

        // ================================================================
        // LINK VARIABLES
        // ================================================================
        variables.put("review_link", "Link to leave a public review (NEW: snake_case)");
        variables.put("reviewLink", "Link to leave a public review (LEGACY: camelCase)");
        variables.put("feedback_link", "Link to private feedback form (NEW: snake_case)");
        variables.put("feedbackLink", "Link to private feedback form (LEGACY: camelCase)");
        variables.put("referral_link", "Referral program link (NEW: snake_case)");

        // ================================================================
        // DATE VARIABLES
        // ================================================================
        variables.put("current_date", "Current date (NEW: snake_case)");
        variables.put("currentDate", "Current date (LEGACY: camelCase)");
        variables.put("current_year", "Current year (NEW: snake_case)");
        variables.put("currentYear", "Current year (LEGACY: camelCase)");

        return variables;
    }

    // ================================================================
    // PRIVATE HELPER METHODS
    // ================================================================

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
        // Use the review link already generated in the ReviewRequest
        if (reviewRequest.getReviewLink() != null && !reviewRequest.getReviewLink().isEmpty()) {
            return reviewRequest.getReviewLink();
        }

        // Fallback: generate feedback gate link
        String baseUrl = "https://app.reputul.com"; // TODO: Make this configurable
        return String.format("%s/feedback-gate/%s", baseUrl, reviewRequest.getCustomer().getId());
    }

    private String generateFeedbackLink(ReviewRequest reviewRequest) {
        // Generate private feedback link
        String baseUrl = "https://app.reputul.com"; // TODO: Make this configurable
        return String.format("%s/feedback/%s", baseUrl, reviewRequest.getCustomer().getId());
    }

    private String generateReferralLink(ReviewRequest reviewRequest) {
        // Generate referral program link
        // TODO: Implement actual referral link generation based on your referral program
        String baseUrl = "https://app.reputul.com"; // TODO: Make this configurable
        Long businessId = reviewRequest.getBusiness().getId();
        Long customerId = reviewRequest.getCustomer().getId();

        return String.format("%s/referral/%s?ref=%s", baseUrl, businessId, customerId);
    }
}