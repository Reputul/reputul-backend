package com.reputul.backend.controllers;

import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.twilio.security.RequestValidator;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Webhook controller for handling SMS delivery/status updates from Twilio.
 * Configure Twilio Messaging Service -> Status Callback: {your-domain}/api/webhooks/sms/status  (POST)
 * If you also wire carrier delivery receipts, you can POST to /api/webhooks/sms/delivery.
 */
@RestController
@RequestMapping("/api/webhooks/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsWebhookController {

    private final ReviewRequestRepository reviewRequestRepository;

    // --- Signature validation configuration ---
    @Value("${sms.webhook.validate-signature:true}")
    private boolean validateSignature;

    @Value("${twilio.authToken:}")
    private String twilioAuthToken;          // prefer this

    @Value("${twilio.auth-token:}")
    private String twilioAuthTokenAlt;       // fallback (different naming)

    @Value("${sms.webhook.secret:}")
    private String legacyWebhookSecret;      // legacy fallback if you already stored it here

    /**
     * Handle SMS status updates from Twilio (primary callback)
     */
    @PostMapping("/status")
    public ResponseEntity<String> handleSmsStatusUpdate(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String twilioSignature,
            HttpServletRequest request) {

        try {
            // -- Optional but recommended: validate Twilio signature
            if (validateSignature) {
                String authToken = resolveAuthToken();
                if (!StringUtils.hasText(authToken)) {
                    log.warn("Twilio signature validation is ENABLED but no auth token is configured. " +
                            "Set twilio.authToken (or TWILIO_AUTH_TOKEN env). Proceeding without validation.");
                } else if (!isSignatureValid(request, params, twilioSignature, authToken)) {
                    log.warn("🚫 Invalid Twilio signature. Rejecting webhook.");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
                }
            }

            // Extract common Twilio params (be tolerant of both MessageStatus and SmsStatus)
            final String messageSid = get(params, "MessageSid");
            final String rawStatus = coalesce(params.get("MessageStatus"), params.get("SmsStatus"));
            final String to = get(params, "To");
            final String from = get(params, "From");
            final String errorCode = params.get("ErrorCode");     // numeric string when failed/undelivered
            final String errorMessage = params.get("ErrorMessage"); // may be null; not always provided

            log.info("📱 Twilio status webhook: SID={}, Status={}, To={}, From={}",
                    messageSid, rawStatus, maskPhoneNumber(to), maskPhoneNumber(from));

            if (!StringUtils.hasText(messageSid) || !StringUtils.hasText(rawStatus)) {
                log.warn("Invalid webhook payload (missing MessageSid or MessageStatus). Payload keys: {}", params.keySet());
                return ResponseEntity.badRequest().body("Missing required parameters");
            }

            final String status = normalizeStatus(rawStatus);

            // Look up our app record
            Optional<ReviewRequest> reviewRequestOpt = reviewRequestRepository.findBySmsMessageId(messageSid);
            if (reviewRequestOpt.isEmpty()) {
                // Not every outbound message will belong to a ReviewRequest; don't 4xx Twilio for that.
                log.warn("No ReviewRequest found for MessageSid={}", messageSid);
                return ResponseEntity.ok("Message not tracked by app");
            }

            ReviewRequest rr = reviewRequestOpt.get();

            // Always record the last raw SMS status & error code for audit
            rr.setSmsStatus(status);
            if (StringUtils.hasText(errorCode)) {
                rr.setSmsErrorCode(errorCode);
            }

            // Monotonic state transition (no regressions)
            updateReviewRequestStatusMonotonic(rr, status, errorMessage);

            reviewRequestRepository.save(rr);
            log.info("✅ ReviewRequest {} updated -> appStatus={}, smsStatus={}, errorCode={}",
                    rr.getId(), rr.getStatus(), rr.getSmsStatus(), rr.getSmsErrorCode());

            return ResponseEntity.ok("Status updated");
        } catch (Exception e) {
            log.error("❌ Error processing SMS status webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    /**
     * Optional: carrier delivery receipts (if configured separately)
     */
    @PostMapping("/delivery")
    public ResponseEntity<String> handleSmsDeliveryReceipt(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String twilioSignature,
            HttpServletRequest request) {

        try {
            if (validateSignature) {
                String authToken = resolveAuthToken();
                if (StringUtils.hasText(authToken) && !isSignatureValid(request, params, twilioSignature, authToken)) {
                    log.warn("🚫 Invalid Twilio signature on /delivery.");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
                }
            }

            final String messageSid = get(params, "MessageSid");
            final String rawStatus = coalesce(params.get("MessageStatus"), params.get("SmsStatus"));
            final String status = normalizeStatus(rawStatus);

            log.info("📬 Delivery receipt: SID={}, Status={}", messageSid, status);

            if (!StringUtils.hasText(messageSid) || !StringUtils.hasText(status)) {
                return ResponseEntity.badRequest().body("Missing MessageSid or MessageStatus");
            }

            reviewRequestRepository.findBySmsMessageId(messageSid)
                    .ifPresent(rr -> {
                        rr.setSmsStatus(status);
                        if ("delivered".equals(status)) {
                            rr.setStatus(ReviewRequest.RequestStatus.DELIVERED);
                            // If you add deliveredAt in your entity, set it here.
                            // rr.setDeliveredAt(OffsetDateTime.now(ZoneOffset.UTC));
                        }
                        reviewRequestRepository.save(rr);
                    });

            return ResponseEntity.ok("Delivery receipt processed");
        } catch (Exception e) {
            log.error("❌ Error processing SMS delivery receipt: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing delivery receipt");
        }
    }

    // ---------- Helpers ----------

    private void updateReviewRequestStatusMonotonic(ReviewRequest rr, String smsStatus, String errorMessage) {
        ReviewRequest.RequestStatus current = rr.getStatus();
        if (current == null) current = ReviewRequest.RequestStatus.PENDING;

        switch (smsStatus) {
            case "queued":
            case "accepted":
            case "sending":
            case "sent":
                // Move to SENT only from PENDING (avoid regressions)
                if (current == ReviewRequest.RequestStatus.PENDING) {
                    rr.setStatus(ReviewRequest.RequestStatus.SENT);
                    if (rr.getSentAt() == null) {
                        rr.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
                    }
                }
                break;

            case "delivered":
                // Don't override CLICKED/COMPLETED
                if (current != ReviewRequest.RequestStatus.CLICKED &&
                        current != ReviewRequest.RequestStatus.COMPLETED) {
                    rr.setStatus(ReviewRequest.RequestStatus.DELIVERED);
                    // If you add deliveredAt in your entity, set it here:
                    // rr.setDeliveredAt(OffsetDateTime.now(ZoneOffset.UTC));
                }
                break;

            case "undelivered":
            case "failed":
                // Only mark as FAILED if we haven't already recorded a successful engagement
                if (current != ReviewRequest.RequestStatus.DELIVERED &&
                        current != ReviewRequest.RequestStatus.CLICKED &&
                        current != ReviewRequest.RequestStatus.COMPLETED) {
                    rr.setStatus(ReviewRequest.RequestStatus.FAILED);
                    if (StringUtils.hasText(errorMessage)) {
                        rr.setErrorMessage("SMS failed: " + errorMessage);
                    }
                    // If you add failedAt in your entity, set it here:
                    // rr.setFailedAt(OffsetDateTime.now(ZoneOffset.UTC));
                }
                break;

            default:
                log.debug("Unhandled/ignored SMS status '{}' for request {}", smsStatus, rr.getId());
        }
    }

    private String normalizeStatus(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // Twilio commonly uses: queued, accepted, sending, sent, delivered, undelivered, failed
        switch (s) {
            case "queued":
            case "accepted":
            case "sending":
            case "sent":
            case "delivered":
            case "undelivered":
            case "failed":
                return s;
            default:
                // Map legacy/variant values if any show up (e.g., "accepted" sometimes appears as "accepted")
                return s;
        }
    }

    private boolean isSignatureValid(HttpServletRequest request,
                                     Map<String, String> params,
                                     String signature,
                                     String authToken) {
        try {
            if (!StringUtils.hasText(signature)) {
                log.warn("Missing X-Twilio-Signature header");
                return false;
            }
            String url = request.getRequestURL().toString();
            String qs = request.getQueryString();
            if (qs != null && !qs.isEmpty()) {
                url = url + "?" + qs;
            }
            RequestValidator validator = new RequestValidator(authToken);
            boolean ok = validator.validate(url, params, signature);
            if (!ok) {
                log.debug("Signature validation failed for URL={} params={}", url, params.keySet());
            }
            return ok;
        } catch (Exception e) {
            log.warn("Signature validation error: {}", e.getMessage());
            return false;
        }
    }

    private String resolveAuthToken() {
        if (StringUtils.hasText(twilioAuthToken)) return twilioAuthToken;
        if (StringUtils.hasText(twilioAuthTokenAlt)) return twilioAuthTokenAlt;
        if (StringUtils.hasText(legacyWebhookSecret)) return legacyWebhookSecret;
        String env = System.getenv("TWILIO_AUTH_TOKEN");
        return StringUtils.hasText(env) ? env : "";
    }

    private String get(Map<String, String> params, String key) {
        return params.getOrDefault(key, "");
    }

    private String coalesce(String a, String b) {
        return StringUtils.hasText(a) ? a : (StringUtils.hasText(b) ? b : "");
    }

    /**
     * Mask phone number for privacy in logs
     */
    private String maskPhoneNumber(String phoneNumber) {
        if (!StringUtils.hasText(phoneNumber) || phoneNumber.length() < 4) return "****";
        int len = phoneNumber.length();
        int prefix = Math.min(3, len);
        int suffix = 4;
        int stars = Math.max(0, len - prefix - suffix);
        return phoneNumber.substring(0, prefix) + "*".repeat(stars) + phoneNumber.substring(len - suffix);
    }

    // ----------- Diagnostics -----------

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-webhook",
                "timestamp", OffsetDateTime.now(ZoneOffset.UTC)
        ));
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getWebhookConfig() {
        String tokenSource =
                StringUtils.hasText(twilioAuthToken) ? "twilio.authToken" :
                        StringUtils.hasText(twilioAuthTokenAlt) ? "twilio.auth-token" :
                                StringUtils.hasText(legacyWebhookSecret) ? "sms.webhook.secret" :
                                        (StringUtils.hasText(System.getenv("TWILIO_AUTH_TOKEN")) ? "TWILIO_AUTH_TOKEN" : "unset");

        return ResponseEntity.ok(Map.of(
                "statusUrl", "/api/webhooks/sms/status",
                "deliveryUrl", "/api/webhooks/sms/delivery",
                "validateSignature", validateSignature,
                "authTokenSource", tokenSource,
                "supportedEvents", new String[]{"queued", "accepted", "sending", "sent", "delivered", "undelivered", "failed"}
        ));
    }
}
