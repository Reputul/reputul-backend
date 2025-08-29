package com.reputul.backend.controllers;

import com.reputul.backend.models.Customer;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.services.SmsService;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Webhook controller for handling inbound SMS messages from customers
 * Processes STOP, START, HELP keywords for SMS compliance
 */
@RestController
@RequestMapping("/api/webhooks/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsInboundWebhookController {

    private final CustomerRepository customerRepository;
    private final SmsService smsService;

    @Value("${twilio.phone_number}")
    private String fromPhoneNumber;

    @Value("${app.business.name:Reputul}")
    private String businessName;

    @Value("${app.support.phone:1-800-REPUTUL}")
    private String supportPhone;

    // Keywords patterns (case-insensitive)
    private static final Pattern STOP_PATTERN = Pattern.compile("\\b(STOP|CANCEL|END|QUIT|UNSUBSCRIBE|REMOVE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_PATTERN = Pattern.compile("\\b(START|UNSTOP|SUBSCRIBE|YES)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_PATTERN = Pattern.compile("\\b(HELP|INFO|SUPPORT)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Handle inbound SMS messages from Twilio
     * Configure this URL in your Twilio console: {your-domain}/api/webhooks/sms/inbound
     */
    @PostMapping("/inbound")
    public ResponseEntity<String> handleInboundSms(@RequestParam Map<String, String> params) {
        try {
            String fromNumber = params.get("From");
            String messageBody = params.get("Body");
            String messageSid = params.get("MessageSid");

            log.info("Inbound SMS received - From: {}, Body: '{}', SID: {}",
                    maskPhoneNumber(fromNumber), messageBody, messageSid);

            if (fromNumber == null || messageBody == null) {
                log.warn("Invalid inbound SMS payload");
                return ResponseEntity.badRequest().body("Missing required parameters");
            }

            // Use your updated repository method
            Optional<Customer> customerOpt = customerRepository.findByPhoneAnyFormat(fromNumber);

            if (customerOpt.isEmpty()) {
                log.warn("Customer not found for phone number: {}", maskPhoneNumber(fromNumber));
                return ResponseEntity.ok("Customer not found");
            }

            Customer customer = customerOpt.get();

            // Process STOP/START keywords
            if (messageBody.toUpperCase().contains("STOP")) {
                customer.recordSmsOptOut(Customer.SmsOptOutMethod.STOP_REPLY);
                customerRepository.save(customer);

                // Send required STOP response
                String response = String.format("STOP: You have been unsubscribed from %s SMS messages. Reply START to resubscribe.",
                        customer.getBusiness().getName());
                // Send auto-reply here using your SMS service

            } else if (messageBody.toUpperCase().contains("START")) {
                customer.recordSmsOptIn(Customer.SmsOptInMethod.DOUBLE_OPT_IN, "sms_start_reply");
                customerRepository.save(customer);

                String response = String.format("Welcome back! You are resubscribed to %s SMS. Reply STOP to opt out.",
                        customer.getBusiness().getName());
                // Send auto-reply here
            }

            return ResponseEntity.ok("Message processed");

        } catch (Exception e) {
            log.error("Error processing inbound SMS: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing message");
        }
    }

    /**
     * Process keyword commands and return appropriate response
     */
    private String processKeywordCommand(Customer customer, String messageBody) {
        String upperMessage = messageBody.toUpperCase();

        // Handle STOP keywords
        if (STOP_PATTERN.matcher(upperMessage).find()) {
            return handleStopCommand(customer);
        }

        // Handle START keywords
        if (START_PATTERN.matcher(upperMessage).find()) {
            return handleStartCommand(customer);
        }

        // Handle HELP keywords
        if (HELP_PATTERN.matcher(upperMessage).find()) {
            return handleHelpCommand(customer);
        }

        // For other messages, provide general help
        log.info("Non-keyword message from customer {}: '{}'", customer.getId(), messageBody);
        return getGeneralHelpResponse(customer);
    }

    /**
     * Handle STOP command - opt customer out of SMS
     */
    private String handleStopCommand(Customer customer) {
        try {
            // Record opt-out
            customer.recordSmsOptOut(Customer.SmsOptOutMethod.STOP_REPLY);
            customerRepository.save(customer);

            log.info("✅ Customer {} opted out via STOP command", customer.getId());

            // Required STOP response per carrier guidelines
            return String.format("STOP: You have been unsubscribed from %s SMS messages. " +
                    "You will not receive any more texts from this number. " +
                    "Reply START to resubscribe. Msg&data rates may apply.", businessName);

        } catch (Exception e) {
            log.error("Error processing STOP command for customer {}: {}", customer.getId(), e.getMessage());
            return "STOP: You have been unsubscribed from SMS messages.";
        }
    }

    /**
     * Handle START command - opt customer back into SMS
     */
    private String handleStartCommand(Customer customer) {
        try {
            // Record opt-in
            customer.recordSmsOptIn(Customer.SmsOptInMethod.DOUBLE_OPT_IN, "sms_start_reply");
            customerRepository.save(customer);

            log.info("✅ Customer {} opted back in via START command", customer.getId());

            return String.format("Welcome back! You have been resubscribed to %s SMS messages. " +
                            "Reply STOP to unsubscribe. Msg&data rates may apply. Support: %s",
                    businessName, supportPhone);

        } catch (Exception e) {
            log.error("Error processing START command for customer {}: {}", customer.getId(), e.getMessage());
            return "You have been resubscribed to SMS messages. Reply STOP to unsubscribe.";
        }
    }

    /**
     * Handle HELP command - provide assistance information
     */
    private String handleHelpCommand(Customer customer) {
        return String.format("%s Support: Reply STOP to unsubscribe, START to resubscribe. " +
                        "We send review requests to help improve our service. " +
                        "Questions? Call %s or email support@reputul.com. Msg&data rates may apply.",
                businessName, supportPhone);
    }

    /**
     * General help response for non-keyword messages
     */
    private String getGeneralHelpResponse(Customer customer) {
        return String.format("Hi %s! Thanks for your message. For review requests from %s, " +
                        "reply HELP for info, STOP to unsubscribe. Support: %s",
                customer.getName().split(" ")[0], businessName, supportPhone);
    }

    /**
     * Send response to unknown phone numbers
     */
    private void sendUnknownNumberResponse(String phoneNumber) {
        try {
            String message = String.format("Hi! This is %s customer service. " +
                            "We don't have your number in our system. " +
                            "For support, please call %s. Msg&data rates may apply.",
                    businessName, supportPhone);

            sendAutoReply(phoneNumber, message);

        } catch (Exception e) {
            log.error("Error sending unknown number response to {}: {}",
                    maskPhoneNumber(phoneNumber), e.getMessage());
        }
    }

    /**
     * Send auto-reply SMS
     */
    private void sendAutoReply(String toNumber, String message) {
        try {
            Message twilioMessage = Message.creator(
                    new PhoneNumber(toNumber),
                    new PhoneNumber(fromPhoneNumber),
                    message
            ).create();

            log.info("✅ Auto-reply sent to {} - SID: {}",
                    maskPhoneNumber(toNumber), twilioMessage.getSid());

        } catch (Exception e) {
            log.error("❌ Failed to send auto-reply to {}: {}",
                    maskPhoneNumber(toNumber), e.getMessage());
        }
    }

    /**
     * Clean phone number for database lookup
     */
    private String cleanPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return null;

        // Remove all non-digits except leading +
        String cleaned = phoneNumber.replaceAll("[^+\\d]", "");

        // Ensure it starts with + for E.164 format
        if (!cleaned.startsWith("+")) {
            if (cleaned.length() == 10) {
                cleaned = "+1" + cleaned; // US number
            } else if (cleaned.length() == 11 && cleaned.startsWith("1")) {
                cleaned = "+" + cleaned; // US number with country code
            } else {
                cleaned = "+" + cleaned; // International
            }
        }

        return cleaned;
    }

    /**
     * Mask phone number for privacy in logs
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
     * Health check endpoint
     */
    @GetMapping("/inbound/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-inbound-webhook",
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC),
                "supportedKeywords", new String[]{"STOP", "START", "HELP"}
        ));
    }

    /**
     * Get inbound webhook configuration info (for debugging)
     */
    @GetMapping("/inbound/config")
    public ResponseEntity<Map<String, Object>> getInboundConfig() {
        return ResponseEntity.ok(Map.of(
                "inboundUrl", "/api/webhooks/sms/inbound",
                "businessName", businessName,
                "supportPhone", supportPhone,
                "fromNumber", fromPhoneNumber,
                "supportedCommands", Map.of(
                        "STOP", "STOP, CANCEL, END, QUIT, UNSUBSCRIBE, REMOVE",
                        "START", "START, UNSTOP, SUBSCRIBE, YES",
                        "HELP", "HELP, INFO, SUPPORT"
                )
        ));
    }
}