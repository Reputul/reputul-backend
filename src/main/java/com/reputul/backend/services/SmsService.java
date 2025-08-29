package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.CustomerRepository;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.time.ZoneOffset;
import java.util.regex.Pattern;
import java.util.Map;
import java.time.OffsetDateTime;

@Service
@Slf4j
public class SmsService {

    @Value("${twilio.account_sid}")
    private String accountSid;

    @Value("${twilio.auth_token}")
    private String authToken;

    @Value("${twilio.phone_number}")
    private String fromPhoneNumber;

    @Value("${sms.daily_limit:100}")
    private int dailySmsLimit;

    @Value("${sms.customer_daily_limit:3}")
    private int customerDailyLimit;

    private final SmsTemplateService smsTemplateService;
    private final SmsRateLimitService rateLimitService;
    private final CustomerRepository customerRepository;

    // Phone number validation pattern (E.164 format)
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    public SmsService(SmsTemplateService smsTemplateService,
                      SmsRateLimitService rateLimitService,
                      CustomerRepository customerRepository) {
        this.smsTemplateService = smsTemplateService;
        this.rateLimitService = rateLimitService;
        this.customerRepository = customerRepository;
    }

    @PostConstruct
    public void initializeTwilio() {
        try {
            if (accountSid != null && authToken != null) {
                Twilio.init(accountSid, authToken);
                log.info("✅ Twilio initialized successfully");
            } else {
                log.warn("⚠️ Twilio credentials not configured. SMS functionality will be disabled.");
            }
        } catch (Exception e) {
            log.error("❌ Failed to initialize Twilio: {}", e.getMessage());
        }
    }

    /**
     * COMPLIANCE: Send review request SMS to customer with full compliance checks
     */
    public SmsResult sendReviewRequestSms(Customer customer) {
        try {
            log.info("🚀 Starting compliance SMS send process for customer {}", customer.getId());

            // STEP 1: Validate SMS consent and eligibility
            SmsEligibilityResult eligibility = checkSmsEligibility(customer);
            if (!eligibility.isEligible()) {
                log.warn("❌ SMS not eligible for customer {}: {}", customer.getId(), eligibility.getReason());
                return SmsResult.failure(eligibility.getReason());
            }

            // STEP 2: Check rate limits (business and customer level)
            SmsRateLimitService.SmsRateLimitResult rateLimitResult =
                    rateLimitService.canSendSms(customer.getBusiness(), customer.getPhone());

            if (!rateLimitResult.isAllowed()) {
                log.warn("❌ Rate limit exceeded for customer {}: {}", customer.getId(), rateLimitResult.getReason());
                return SmsResult.failure("Rate limit exceeded: " + rateLimitResult.getReason());
            }

            // STEP 3: Check customer-specific daily limit
            if (customer.hasReachedDailySmsLimit(customerDailyLimit)) {
                log.warn("❌ Customer {} has reached daily SMS limit", customer.getId());
                return SmsResult.failure("Customer daily SMS limit reached");
            }

            // STEP 4: Generate compliant message
            String message = smsTemplateService.generateReviewRequestMessage(customer);

            // STEP 5: Validate message compliance
            SmsTemplateService.SmsComplianceValidationResult complianceResult =
                    smsTemplateService.validateCompliance(message);

            if (!complianceResult.isCompliant()) {
                log.error("❌ SMS message not compliant for customer {}: {}",
                        customer.getId(), complianceResult.getIssues());
                return SmsResult.failure("Message compliance failed: " + complianceResult.getIssues());
            }

            // STEP 6: Send SMS
            SmsResult sendResult = sendSms(customer.getPhone(), message);

            if (sendResult.isSuccess()) {
                // STEP 7: Record usage and update customer
                rateLimitService.recordSmsUsage(customer.getBusiness());
                customer.incrementSmsSendCount();
                customerRepository.save(customer);

                log.info("✅ Compliance SMS sent successfully to customer {}", customer.getId());
            }

            return sendResult;

        } catch (Exception e) {
            log.error("❌ Failed to send compliance review request SMS to customer {}: {}",
                    customer.getId(), e.getMessage(), e);
            return SmsResult.failure("Error sending SMS: " + e.getMessage());
        }
    }

    /**
     * COMPLIANCE: Send follow-up SMS with full compliance checks
     */
    public SmsResult sendFollowUpSms(Customer customer, String followUpType) {
        try {
            log.info("🔄 Starting compliance follow-up SMS for customer {}, type: {}",
                    customer.getId(), followUpType);

            // Same compliance checks as review request
            SmsEligibilityResult eligibility = checkSmsEligibility(customer);
            if (!eligibility.isEligible()) {
                return SmsResult.failure(eligibility.getReason());
            }

            SmsRateLimitService.SmsRateLimitResult rateLimitResult =
                    rateLimitService.canSendSms(customer.getBusiness(), customer.getPhone());

            if (!rateLimitResult.isAllowed()) {
                return SmsResult.failure("Rate limit exceeded: " + rateLimitResult.getReason());
            }

            if (customer.hasReachedDailySmsLimit(customerDailyLimit)) {
                return SmsResult.failure("Customer daily SMS limit reached");
            }

            String message = smsTemplateService.generateFollowUpMessage(customer, followUpType);

            SmsTemplateService.SmsComplianceValidationResult complianceResult =
                    smsTemplateService.validateCompliance(message);

            if (!complianceResult.isCompliant()) {
                return SmsResult.failure("Message compliance failed: " + complianceResult.getIssues());
            }

            SmsResult sendResult = sendSms(customer.getPhone(), message);

            if (sendResult.isSuccess()) {
                rateLimitService.recordSmsUsage(customer.getBusiness());
                customer.incrementSmsSendCount();
                customerRepository.save(customer);
            }

            return sendResult;

        } catch (Exception e) {
            log.error("❌ Failed to send follow-up SMS to customer {}: {}", customer.getId(), e.getMessage(), e);
            return SmsResult.failure("Error sending follow-up SMS: " + e.getMessage());
        }
    }

    /**
     * COMPLIANCE: Send test SMS (admin/testing only)
     */
    public SmsResult sendTestSms(String phoneNumber, String message) {
        try {
            if (!isValidPhoneNumber(phoneNumber)) {
                return SmsResult.failure("Invalid phone number format");
            }

            // For test SMS, we still validate compliance but skip consent checks
            SmsTemplateService.SmsComplianceValidationResult complianceResult =
                    smsTemplateService.validateCompliance(message);

            if (!complianceResult.isCompliant()) {
                log.warn("⚠️ Test SMS not fully compliant: {}", complianceResult.getIssues());
                // Continue anyway for testing, but log the issues
            }

            return sendSms(phoneNumber, message);

        } catch (Exception e) {
            log.error("❌ Failed to send test SMS to {}: {}", maskPhoneNumber(phoneNumber), e.getMessage());
            return SmsResult.failure("Error sending test SMS: " + e.getMessage());
        }
    }

    /**
     * COMPLIANCE: Check if customer is eligible to receive SMS
     */
    private SmsEligibilityResult checkSmsEligibility(Customer customer) {
        // Check if customer can receive SMS based on consent
        if (!customer.canReceiveSms()) {
            String reason = "Customer not eligible for SMS: ";

            if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
                reason += "no phone number";
            } else if (customer.getSmsOptOut() != null && customer.getSmsOptOut()) {
                reason += "opted out on " + customer.getSmsOptOutTimestamp();
            } else if (customer.getSmsOptIn() == null || !customer.getSmsOptIn()) {
                reason += "no SMS consent recorded";
            } else {
                reason += "unknown eligibility issue";
            }

            return SmsEligibilityResult.notEligible(reason);
        }

        // Validate phone number format
        if (!isValidPhoneNumber(customer.getPhone())) {
            return SmsEligibilityResult.notEligible("Invalid phone number format: " + customer.getPhone());
        }

        return SmsEligibilityResult.eligible();
    }

    /**
     * Core SMS sending method with enhanced error handling
     */
    private SmsResult sendSms(String toPhoneNumber, String messageContent) {
        try {
            String formattedPhone = formatPhoneNumber(toPhoneNumber);

            // Validate message length with better handling
            if (messageContent.length() > 1600) {
                log.warn("SMS message too long ({} chars), truncating", messageContent.length());
                messageContent = messageContent.substring(0, 1597) + "...";
            }

            // Send via Twilio with retry logic
            Message message = sendWithRetry(formattedPhone, messageContent, 3);

            log.info("✅ SMS sent successfully to {} - SID: {}",
                    maskPhoneNumber(formattedPhone), message.getSid());

            return SmsResult.success(message.getSid(), message.getStatus().toString());

        } catch (Exception e) {
            log.error("❌ Failed to send SMS to {}: {}", maskPhoneNumber(toPhoneNumber), e.getMessage());
            return SmsResult.failure("Failed to send SMS: " + e.getMessage());
        }
    }

    /**
     * Send SMS with retry logic for better reliability
     */
    private Message sendWithRetry(String toPhoneNumber, String messageContent, int maxRetries) {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return Message.creator(
                        new PhoneNumber(toPhoneNumber),
                        new PhoneNumber(fromPhoneNumber),
                        messageContent
                ).create();

            } catch (Exception e) {
                lastException = e;
                log.warn("SMS send attempt {} of {} failed for {}: {}",
                        attempt, maxRetries, maskPhoneNumber(toPhoneNumber), e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("SMS send interrupted", ie);
                    }
                }
            }
        }

        throw new RuntimeException("SMS send failed after " + maxRetries + " attempts", lastException);
    }

    /**
     * Validate phone number format
     */
    public boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        String cleanedNumber = phoneNumber.replaceAll("[\\s\\-\\(\\)\\.]", "");
        return PHONE_PATTERN.matcher(cleanedNumber).matches() ||
                PHONE_PATTERN.matcher("+" + cleanedNumber).matches();
    }

    /**
     * Format phone number to E.164 format
     */
    private String formatPhoneNumber(String phoneNumber) {
        String cleaned = phoneNumber.replaceAll("[\\s\\-\\(\\)\\.]", "");

        if (!cleaned.startsWith("+")) {
            if (cleaned.length() == 10 && cleaned.matches("\\d{10}")) {
                return "+1" + cleaned;
            } else if (cleaned.length() == 11 && cleaned.startsWith("1")) {
                return "+" + cleaned;
            } else {
                return "+" + cleaned;
            }
        }

        return cleaned;
    }

    /**
     * Mask phone number for logging privacy
     */
    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        return phoneNumber.substring(0, Math.min(3, phoneNumber.length())) +
                "*".repeat(Math.max(0, phoneNumber.length() - 7)) +
                phoneNumber.substring(Math.max(3, phoneNumber.length() - 4));
    }

    /**
     * COMPLIANCE: Get SMS eligibility status for customer
     */
    public SmsEligibilityResult getSmsEligibility(Customer customer) {
        return checkSmsEligibility(customer);
    }

    /**
     * COMPLIANCE: Record SMS opt-in for customer
     */
    public boolean recordSmsOptIn(Customer customer, Customer.SmsOptInMethod method, String source) {
        try {
            customer.recordSmsOptIn(method, source);
            customerRepository.save(customer);

            log.info("✅ SMS opt-in recorded for customer {} via {} from {}",
                    customer.getId(), method, source);
            return true;

        } catch (Exception e) {
            log.error("❌ Failed to record SMS opt-in for customer {}: {}",
                    customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Check if SMS quota is available (implement based on business rules)
     */
    public boolean canSendSms(String userEmail) {
        // Enhanced quota checking with rate limiting service
        // For now, return true. Full implementation would check user plans, etc.
        return true;
    }

    /**
     * Get SMS delivery status from Twilio
     */
    public String getSmsStatus(String messageSid) {
        try {
            Message message = Message.fetcher(messageSid).fetch();
            return message.getStatus().toString();
        } catch (Exception e) {
            log.error("Failed to fetch SMS status for SID {}: {}", messageSid, e.getMessage());
            return "unknown";
        }
    }

    /**
     * Get SMS service status and configuration
     */
    public SmsServiceStatus getServiceStatus() {
        SmsRateLimitService.SmsRateLimitConfig config = rateLimitService.getConfiguration();
        boolean quietHours = rateLimitService.isQuietHours();

        return new SmsServiceStatus(
                accountSid != null && authToken != null,
                fromPhoneNumber,
                config,
                quietHours,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    // Helper classes
    public static class SmsResult {
        private final boolean success;
        private final String messageSid;
        private final String status;
        private final String errorMessage;

        private SmsResult(boolean success, String messageSid, String status, String errorMessage) {
            this.success = success;
            this.messageSid = messageSid;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public static SmsResult success(String messageSid, String status) {
            return new SmsResult(true, messageSid, status, null);
        }

        public static SmsResult failure(String errorMessage) {
            return new SmsResult(false, null, null, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public String getMessageSid() { return messageSid; }
        public String getStatus() { return status; }
        public String getErrorMessage() { return errorMessage; }
    }

    public static class SmsEligibilityResult {
        private final boolean eligible;
        private final String reason;

        private SmsEligibilityResult(boolean eligible, String reason) {
            this.eligible = eligible;
            this.reason = reason;
        }

        public static SmsEligibilityResult eligible() {
            return new SmsEligibilityResult(true, null);
        }

        public static SmsEligibilityResult notEligible(String reason) {
            return new SmsEligibilityResult(false, reason);
        }

        public boolean isEligible() { return eligible; }
        public String getReason() { return reason; }
    }

    public static class SmsServiceStatus {
        private final boolean twilioConfigured;
        private final String fromPhoneNumber;
        private final SmsRateLimitService.SmsRateLimitConfig rateLimitConfig;
        private final boolean currentlyQuietHours;
        private final OffsetDateTime timestamp;

        public SmsServiceStatus(boolean twilioConfigured, String fromPhoneNumber,
                                SmsRateLimitService.SmsRateLimitConfig rateLimitConfig,
                                boolean currentlyQuietHours, OffsetDateTime timestamp) {
            this.twilioConfigured = twilioConfigured;
            this.fromPhoneNumber = fromPhoneNumber;
            this.rateLimitConfig = rateLimitConfig;
            this.currentlyQuietHours = currentlyQuietHours;
            this.timestamp = timestamp;
        }

        // Getters
        public boolean isTwilioConfigured() { return twilioConfigured; }
        public String getFromPhoneNumber() { return fromPhoneNumber; }
        public SmsRateLimitService.SmsRateLimitConfig getRateLimitConfig() { return rateLimitConfig; }
        public boolean isCurrentlyQuietHours() { return currentlyQuietHours; }
        public OffsetDateTime getTimestamp() { return timestamp; }
    }
}