package com.reputul.backend.controllers;

import com.reputul.backend.models.Customer;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.services.SmsService;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.security.RequestValidator;
import com.twilio.type.PhoneNumber;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Inbound SMS webhook (Twilio -> Reputul).
 * Configure Twilio Messaging Service -> Inbound "A message comes in" (POST) -> {your-domain}/api/webhooks/sms/inbound
 */
@RestController
@RequestMapping("/api/webhooks/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsInboundWebhookController {

    private final CustomerRepository customerRepository;
    private final SmsService smsService;

    @Value("${twilio.phone_number:}")
    private String fromPhoneNumber;

    @Value("${twilio.messaging_service_sid:}")
    private String messagingServiceSid;

    @Value("${twilio.authToken:}")
    private String twilioAuthToken;

    @Value("${twilio.auth-token:}") // alt key if you used hyphenated name
    private String twilioAuthTokenAlt;

    @Value("${sms.webhook.validate-signature:true}")
    private boolean validateSignature;

    @Value("${app.business.name:Reputul}")
    private String businessName;

    @Value("${app.support.phone:}")
    private String supportPhone;

    @Value("${app.support.email:support@reputul.com}")
    private String supportEmail;

    // Expanded keyword patterns (case-insensitive)
    private static final Pattern STOP_PATTERN  = Pattern.compile("\\b(STOP|STOPALL|CANCEL|END|QUIT|UNSUBSCRIBE|REMOVE|REVOKE|OPTOUT)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern START_PATTERN = Pattern.compile("\\b(START|UNSTOP|SUBSCRIBE|YES)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HELP_PATTERN  = Pattern.compile("\\b(HELP|INFO|SUPPORT)\\b", Pattern.CASE_INSENSITIVE);

    // Marker: let Twilio Advanced Opt-Out handle the auto-reply
    private static final String TWILIO_HANDLED_MARKER = "__TWILIO_HANDLED__";

    @PostMapping("/inbound")
    public ResponseEntity<String> handleInboundSms(@RequestParam Map<String, String> params,
                                                   @RequestHeader(value = "X-Twilio-Signature", required = false) String signature,
                                                   HttpServletRequest request) {
        try {
            // Signature validation (highly recommended)
            if (validateSignature) {
                String token = resolveAuthToken();
                if (StringUtils.hasText(token)) {
                    if (!isSignatureValid(request, params, signature, token)) {
                        log.warn("🚫 Invalid Twilio signature on /inbound");
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
                    }
                } else {
                    log.warn("Twilio signature validation enabled but no auth token configured. Set twilio.authToken or TWILIO_AUTH_TOKEN.");
                }
            }

            final String fromNumber = params.get("From");
            final String body       = Optional.ofNullable(params.get("Body")).orElse("");
            final String messageSid = params.get("MessageSid");

            log.info("📩 Inbound SMS: From={}, SID={}, Body='{}'",
                    maskPhoneNumber(fromNumber), messageSid, body);

            if (!StringUtils.hasText(fromNumber) || !StringUtils.hasText(body)) {
                return ResponseEntity.badRequest().body("Missing required parameters");
            }

            // Find the customer by any phone format you support
            Optional<Customer> customerOpt = customerRepository.findByPhoneAnyFormat(fromNumber);

            if (customerOpt.isEmpty()) {
                log.warn("Customer not found for inbound number {}", maskPhoneNumber(fromNumber));
                sendUnknownNumberResponse(fromNumber);
                return ResponseEntity.ok("Customer not found");
            }

            Customer customer = customerOpt.get();

            // Process STOP/START/HELP (YES treated as START unless you implement a "pending confirmation" state)
            String response = processKeywordCommand(customer, body);

            // Avoid double replies when Twilio Advanced Opt-Out handles STOP/HELP
            if (StringUtils.hasText(response) && !TWILIO_HANDLED_MARKER.equals(response)) {
                sendAutoReply(fromNumber, response);
            } else if (TWILIO_HANDLED_MARKER.equals(response)) {
                log.info("ℹ️ Auto-reply suppressed (Twilio will respond to keyword).");
            }

            return ResponseEntity.ok("Message processed");
        } catch (Exception e) {
            log.error("❌ Error processing inbound SMS: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing message");
        }
    }

    // ---- Keyword handling ----

    private String processKeywordCommand(Customer customer, String messageBody) {
        String upper = messageBody == null ? "" : messageBody.toUpperCase(Locale.ROOT);

        if (STOP_PATTERN.matcher(upper).find()) {
            return handleStopCommand(customer);
        }
        if (START_PATTERN.matcher(upper).find()) {
            // If you have a "pending double opt-in" state, you can branch here on YES vs START
            return handleStartCommand(customer, upper.contains("YES"));
        }
        if (HELP_PATTERN.matcher(upper).find()) {
            return handleHelpCommand(customer);
        }

        // Non-keyword message → gentle guidance
        log.info("ℹ️ Non-keyword inbound from customer {}: '{}'", customer.getId(), messageBody);
        return getGeneralHelpResponse(customer);
    }

    private String handleStopCommand(Customer customer) {
        try {
            customer.recordSmsOptOut(Customer.SmsOptOutMethod.STOP_REPLY);
            customerRepository.save(customer);
            log.info("✅ Customer {} opted out via STOP", customer.getId());

            // Let Twilio send the standard STOP confirmation if Advanced Opt-Out is enabled
            return TWILIO_HANDLED_MARKER;

            // If you want to send your own STOP confirmation instead, return a string here.
        } catch (Exception e) {
            log.error("STOP handling failed for customer {}: {}", customer.getId(), e.getMessage());
            return "You have been unsubscribed from SMS messages.";
        }
    }

    private String handleStartCommand(Customer customer, boolean isYes) {
        try {
            // Record (re)opt-in — method label hints at path; adjust to your enum/fields
            customer.recordSmsOptIn(
                    isYes ? Customer.SmsOptInMethod.DOUBLE_OPT_IN : Customer.SmsOptInMethod.SMS_START_REPLY,
                    isYes ? "sms_yes_reply" : "sms_start_reply"
            );
            customerRepository.save(customer);

            log.info("✅ Customer {} opted in via {}", customer.getId(), isYes ? "YES" : "START");

            return String.format(
                    "Welcome! You’re subscribed to %s SMS. Reply STOP to unsubscribe, HELP for help. Msg&data rates may apply. Support: %s",
                    businessName,
                    StringUtils.hasText(supportEmail) ? supportEmail : "support@example.com"
            );
        } catch (Exception e) {
            log.error("START/YES handling failed for customer {}: {}", customer.getId(), e.getMessage());
            return "You have been resubscribed. Reply STOP to unsubscribe.";
        }
    }

    private String handleHelpCommand(Customer customer) {
        // With Advanced Opt-Out on Messaging Service, Twilio sends a HELP reply.
        return TWILIO_HANDLED_MARKER;

        // If you prefer your own HELP reply, return something like:
        // return String.format("%s Support: Reply STOP to unsubscribe, START to resubscribe. Msg&data rates may apply. %s %s",
        //         businessName,
        //         StringUtils.hasText(supportPhone) ? ("Call " + supportPhone + ".") : "",
        //         StringUtils.hasText(supportEmail) ? ("Email " + supportEmail + ".") : "");
    }

    private String getGeneralHelpResponse(Customer customer) {
        String firstName = Optional.ofNullable(customer.getName()).orElse("there").split(" ")[0];
        return String.format("Hi %s! For review requests from %s, reply HELP for info, STOP to unsubscribe. Support: %s",
                firstName, businessName, supportEmail);
    }

    private void sendUnknownNumberResponse(String phoneNumber) {
        try {
            String msg = String.format(
                    "Hi! This is %s. We don’t recognize this number in our system. For support, email %s. Msg&data rates may apply.",
                    businessName, supportEmail);
            sendAutoReply(phoneNumber, msg);
        } catch (Exception e) {
            log.error("Failed sending unknown-number reply to {}: {}", maskPhoneNumber(phoneNumber), e.getMessage());
        }
    }

    private void sendAutoReply(String toNumber, String message) {
        try {
            Message twilioMessage;
            if (StringUtils.hasText(messagingServiceSid)) {
                // Prefer Messaging Service SID
                twilioMessage = Message.creator(new PhoneNumber(toNumber), messagingServiceSid, message).create();
            } else {
                // Fallback to explicit From (toll-free)
                twilioMessage = Message.creator(new PhoneNumber(toNumber), new PhoneNumber(fromPhoneNumber), message).create();
            }
            log.info("✅ Auto-reply sent to {} SID={}", maskPhoneNumber(toNumber), twilioMessage.getSid());
        } catch (Exception e) {
            log.error("❌ Auto-reply send failed to {}: {}", maskPhoneNumber(toNumber), e.getMessage());
        }
    }

    // ---- Utilities ----

    private boolean isSignatureValid(HttpServletRequest request,
                                     Map<String, String> params,
                                     String signature,
                                     String authToken) {
        try {
            if (!StringUtils.hasText(signature)) {
                log.warn("Missing X-Twilio-Signature");
                return false;
            }
            String url = request.getRequestURL().toString();
            String qs = request.getQueryString();
            if (StringUtils.hasText(qs)) url = url + "?" + qs;
            RequestValidator validator = new RequestValidator(authToken);
            boolean ok = validator.validate(url, params, signature);
            if (!ok) log.debug("Signature validation failed for URL={} params={}", url, params.keySet());
            return ok;
        } catch (Exception e) {
            log.warn("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private String resolveAuthToken() {
        if (StringUtils.hasText(twilioAuthToken)) return twilioAuthToken;
        if (StringUtils.hasText(twilioAuthTokenAlt)) return twilioAuthTokenAlt;
        String env = System.getenv("TWILIO_AUTH_TOKEN");
        return StringUtils.hasText(env) ? env : "";
    }

    @SuppressWarnings("unused")
    private String cleanPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return null;
        String cleaned = phoneNumber.replaceAll("[^+\\d]", "");
        if (!cleaned.startsWith("+")) {
            if (cleaned.length() == 10) cleaned = "+1" + cleaned;
            else if (cleaned.length() == 11 && cleaned.startsWith("1")) cleaned = "+" + cleaned;
            else cleaned = "+" + cleaned;
        }
        return cleaned;
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber) || phoneNumber.length() < 4) return "****";
        int len = phoneNumber.length();
        int prefix = Math.min(3, len);
        int suffix = 4;
        int stars = Math.max(0, len - prefix - suffix);
        return phoneNumber.substring(0, prefix) + "*".repeat(stars) + phoneNumber.substring(len - suffix);
    }

    // ---- Diagnostics ----

    @GetMapping("/inbound/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-inbound-webhook",
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC),
                "supportedKeywords", new String[]{"STOP", "START/YES", "HELP"},
                "advancedOptOutExpected", true
        ));
    }

    @GetMapping("/inbound/config")
    public ResponseEntity<Map<String, Object>> getInboundConfig() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("inboundUrl", "/api/webhooks/sms/inbound");
        cfg.put("businessName", businessName);
        cfg.put("supportEmail", supportEmail);
        cfg.put("fromNumber", fromPhoneNumber);
        cfg.put("messagingServiceSid", messagingServiceSid);
        cfg.put("twilioSignatureValidation", validateSignature);
        cfg.put("authTokenPresent", StringUtils.hasText(resolveAuthToken()));
        cfg.put("keywords", Map.of(
                "STOP", "STOP, STOPALL, CANCEL, END, QUIT, UNSUBSCRIBE, REMOVE, REVOKE, OPTOUT",
                "START/Opt-in", "START, UNSTOP, SUBSCRIBE, YES",
                "HELP", "HELP, INFO, SUPPORT"
        ));
        return ResponseEntity.ok(cfg);
    }
}
