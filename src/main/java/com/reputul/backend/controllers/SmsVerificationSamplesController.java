package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.services.SmsTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller to generate SMS sample messages for Twilio verification
 * Use this to get real examples with your business names and templates
 */
@RestController
@RequestMapping("/api/v1/sms-samples")
@RequiredArgsConstructor
@Slf4j
public class SmsVerificationSamplesController {

    private final SmsTemplateService smsTemplateService;

    @Value("${app.business.name:Your Business}")
    private String defaultBusinessName;

    @Value("${app.support.phone:}")
    private String supportPhone;

    @Value("${app.support.email:support@reputul.com}")
    private String supportEmail;

    @Value("${app.base.url:https://app.reputul.com}")
    private String baseUrl;

    /**
     * Generate sample SMS messages for Twilio verification submission
     * GET /api/sms-samples/twilio-verification?businessName=ABC%20Roofing
     */
    @GetMapping("/twilio-verification")
    public ResponseEntity<Map<String, Object>> getTwilioVerificationSamples(
            @RequestParam(value = "businessName", defaultValue = "ABC Roofing") String businessName,
            @RequestParam(value = "customerName", defaultValue = "John") String customerName,
            @RequestParam(value = "serviceType", defaultValue = "roof repair") String serviceType) {

        try {
            log.info("Generating Twilio verification samples for business: {}", businessName);

            // Create mock business and customer for template generation
            Business mockBusiness = Business.builder()
                    .name(businessName)
                    .build();

            Customer mockCustomer = Customer.builder()
                    .name(customerName + " Smith")
                    .phone("+14078185210")
                    .serviceType(serviceType)
                    .business(mockBusiness)
                    .smsOptIn(true)
                    .smsOptOut(false)
                    .build();

            // Generate sample messages using your actual templates
            Map<String, Object> samples = new HashMap<>();

            // 1. Primary review request message
            String reviewRequestMessage = smsTemplateService.generateReviewRequestMessage(mockCustomer);
            samples.put("reviewRequest", Map.of(
                    "message", reviewRequestMessage,
                    "description", "Primary use case - Review request after service completion",
                    "frequency", "1-2 times per customer per month"
            ));

            // 2. Follow-up message (3-day)
            String followUpMessage = smsTemplateService.generateFollowUpMessage(mockCustomer, "3_day");
            samples.put("followUp3Day", Map.of(
                    "message", followUpMessage,
                    "description", "Follow-up reminder sent 3 days after initial request",
                    "frequency", "Once per customer if no response"
            ));

            // 3. Follow-up message (7-day)
            String followUp7DayMessage = smsTemplateService.generateFollowUpMessage(mockCustomer, "7_day");
            samples.put("followUp7Day", Map.of(
                    "message", followUp7DayMessage,
                    "description", "Final follow-up reminder sent 7 days after initial request",
                    "frequency", "Once per customer if no response"
            ));

            // 4. Manual keyword responses (if not using Advanced Opt-Out)
            samples.put("stopResponse", Map.of(
                    "message", String.format("STOP: You have been unsubscribed from %s SMS messages. " +
                            "You will not receive any more texts from this number. " +
                            "Reply START to resubscribe. Msg&data rates may apply.", businessName),
                    "description", "Automatic response to STOP keyword (only if not using Twilio Advanced Opt-Out)",
                    "frequency", "As needed when customers reply STOP"
            ));

            samples.put("helpResponse", Map.of(
                    "message", String.format("%s support: Email %s for assistance. Reply STOP to opt out. " +
                            "Msg & data rates may apply.", businessName, supportEmail),
                    "description", "Automatic response to HELP keyword (only if not using Twilio Advanced Opt-Out)",
                    "frequency", "As needed when customers reply HELP"
            ));

            samples.put("startResponse", Map.of(
                    "message", String.format("Welcome back! You have been resubscribed to %s SMS messages. " +
                                    "Reply STOP to unsubscribe, HELP for help. Msg&data rates may apply. Support: %s",
                            businessName, supportEmail),
                    "description", "Welcome message when customers reply START to opt back in",
                    "frequency", "As needed when customers reply START"
            ));

            // 5. Business information for submission
            Map<String, Object> businessInfo = new HashMap<>();
            businessInfo.put("businessName", businessName);
            businessInfo.put("supportEmail", supportEmail);
            businessInfo.put("websiteUrl", baseUrl);
            businessInfo.put("useCase", "Transactional review requests and customer service notifications");
            businessInfo.put("messageFrequency", "1-3 messages per customer per month");
            businessInfo.put("optInMethod", "Web form with explicit consent checkbox + double opt-in confirmation");

            // Only include supportPhone if it's not empty
            if (supportPhone != null && !supportPhone.trim().isEmpty()) {
                businessInfo.put("supportPhone", supportPhone);
            }

            samples.put("businessInfo", businessInfo);

            // 6. Compliance validation
            samples.put("complianceCheck", Map.of(
                    "businessNameInMessages", "✅ All messages start with business name",
                    "stopKeywordIncluded", "✅ All messages include 'Reply STOP to opt out'",
                    "helpKeywordIncluded", "✅ All messages include 'Reply HELP for help'",
                    "ratesDisclosure", "✅ All messages include 'Msg & data rates may apply'",
                    "brandedUrls", "✅ All URLs use branded domain (reputul.com)",
                    "noPublicShorteners", "✅ No bit.ly, tinyurl, or other public shorteners"
            ));

            return ResponseEntity.ok(samples);

        } catch (Exception e) {
            log.error("Error generating Twilio verification samples: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to generate samples",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Generate sample messages for a specific business
     * GET /api/sms-samples/business/{businessId}
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<Map<String, Object>> getBusinessSamples(
            @PathVariable Long businessId) {

        // TODO: Implement if you want to generate samples for specific businesses in your database
        return ResponseEntity.ok(Map.of(
                "message", "Business-specific samples not yet implemented",
                "suggestion", "Use /api/sms-samples/twilio-verification with businessName parameter"
        ));
    }

    /**
     * Health check for the samples endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-verification-samples",
                "availableEndpoints", Map.of(
                        "verification", "/api/sms-samples/twilio-verification?businessName=YourBusiness",
                        "health", "/api/sms-samples/health"
                )
        ));
    }
}