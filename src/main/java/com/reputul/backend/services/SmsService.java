package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.regex.Pattern;
import java.util.Map;

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

    private final SmsTemplateService smsTemplateService;

    // Phone number validation pattern (E.164 format)
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    public SmsService(SmsTemplateService smsTemplateService) {
        this.smsTemplateService = smsTemplateService;
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
     * Send review request SMS to customer
     */
    public SmsResult sendReviewRequestSms(Customer customer) {
        try {
            // Validate phone number
            if (!isValidPhoneNumber(customer.getPhone())) {
                log.warn("Invalid phone number for customer {}: {}", customer.getId(), customer.getPhone());
                return SmsResult.failure("Invalid phone number format");
            }

            // Generate SMS message from template
            String message = smsTemplateService.generateReviewRequestMessage(customer);

            // Send SMS
            return sendSms(customer.getPhone(), message);

        } catch (Exception e) {
            log.error("❌ Failed to send review request SMS to customer {}: {}", customer.getId(), e.getMessage());
            return SmsResult.failure("Error sending SMS: " + e.getMessage());
        }
    }

    /**
     * Send follow-up SMS to customer
     */
    public SmsResult sendFollowUpSms(Customer customer, String followUpType) {
        try {
            if (!isValidPhoneNumber(customer.getPhone())) {
                return SmsResult.failure("Invalid phone number format");
            }

            String message = smsTemplateService.generateFollowUpMessage(customer, followUpType);
            return sendSms(customer.getPhone(), message);

        } catch (Exception e) {
            log.error("❌ Failed to send follow-up SMS to customer {}: {}", customer.getId(), e.getMessage());
            return SmsResult.failure("Error sending follow-up SMS: " + e.getMessage());
        }
    }

    /**
     * Send test SMS
     */
    public SmsResult sendTestSms(String phoneNumber, String message) {
        try {
            if (!isValidPhoneNumber(phoneNumber)) {
                return SmsResult.failure("Invalid phone number format");
            }

            return sendSms(phoneNumber, message);

        } catch (Exception e) {
            log.error("❌ Failed to send test SMS to {}: {}", phoneNumber, e.getMessage());
            return SmsResult.failure("Error sending test SMS: " + e.getMessage());
        }
    }

    /**
     * Core SMS sending method
     */
    private SmsResult sendSms(String toPhoneNumber, String messageContent) {
        try {
            // Ensure phone number has country code
            String formattedPhone = formatPhoneNumber(toPhoneNumber);

            // Validate message length (SMS limit is 1600 characters for concatenated messages)
            if (messageContent.length() > 1600) {
                log.warn("SMS message too long ({} chars), truncating", messageContent.length());
                messageContent = messageContent.substring(0, 1597) + "...";
            }

            // Send via Twilio
            Message message = Message.creator(
                    new PhoneNumber(formattedPhone),
                    new PhoneNumber(fromPhoneNumber),
                    messageContent
            ).create();

            log.info("✅ SMS sent successfully to {} - SID: {}",
                    maskPhoneNumber(formattedPhone), message.getSid());

            return SmsResult.success(message.getSid(), message.getStatus().toString());

        } catch (Exception e) {
            log.error("❌ Failed to send SMS to {}: {}", maskPhoneNumber(toPhoneNumber), e.getMessage());
            return SmsResult.failure("Failed to send SMS: " + e.getMessage());
        }
    }

    /**
     * Validate phone number format
     */
    public boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }

        // Remove common formatting characters
        String cleanedNumber = phoneNumber.replaceAll("[\\s\\-\\(\\)\\.]", "");

        // Check if it matches E.164 format or can be converted to it
        return PHONE_PATTERN.matcher(cleanedNumber).matches() ||
                PHONE_PATTERN.matcher("+" + cleanedNumber).matches();
    }

    /**
     * Format phone number to E.164 format
     */
    private String formatPhoneNumber(String phoneNumber) {
        String cleaned = phoneNumber.replaceAll("[\\s\\-\\(\\)\\.]", "");

        // If it doesn't start with +, assume US number and add +1
        if (!cleaned.startsWith("+")) {
            if (cleaned.length() == 10 && cleaned.matches("\\d{10}")) {
                return "+1" + cleaned; // US format
            } else if (cleaned.length() == 11 && cleaned.startsWith("1")) {
                return "+" + cleaned; // US with country code
            } else {
                return "+" + cleaned; // International format assumed
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
     * Check if SMS quota is available (implement based on your business rules)
     */
    public boolean canSendSms(String userEmail) {
        // TODO: Implement quota checking based on user plan/usage
        // For now, return true. You can implement Redis-based counting or database tracking
        return true;
    }

    /**
     * Get SMS delivery status from Twilio (for webhook processing)
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
     * Result class for SMS operations
     */
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

        // Getters
        public boolean isSuccess() { return success; }
        public String getMessageSid() { return messageSid; }
        public String getStatus() { return status; }
        public String getErrorMessage() { return errorMessage; }
    }
}