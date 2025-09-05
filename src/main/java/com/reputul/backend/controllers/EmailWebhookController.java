package com.reputul.backend.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.repositories.ReviewRequestRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Webhook controller for handling SendGrid email events.
 * Configure SendGrid Event Webhook: {your-domain}/api/webhooks/email/sendgrid (POST)
 * Events: delivered, open, click, bounce, dropped, deferred, processed, etc.
 */
@RestController
@RequestMapping("/api/webhooks/email")
@RequiredArgsConstructor
@Slf4j
public class EmailWebhookController {

    private final ReviewRequestRepository reviewRequestRepository;
    private final ObjectMapper objectMapper;

    // Signature validation configuration
    @Value("${sendgrid.webhook.validate-signature:true}")
    private boolean validateSignature;

    @Value("${sendgrid.webhook.verification-key:}")
    private String sendgridVerificationKey;

    @Value("${email.webhook.secret:}")
    private String legacyWebhookSecret;

    /**
     * Handle SendGrid email events
     * Events: processed, delivered, open, click, bounce, dropped, deferred, etc.
     */
    @PostMapping("/sendgrid")
    public ResponseEntity<String> handleSendGridEvents(
            @RequestBody String requestBody,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Timestamp", required = false) String timestamp,
            HttpServletRequest request) {

        try {
            log.info("SendGrid webhook received. Body length: {}", requestBody.length());

            // Optional signature validation
            if (validateSignature) {
                String verificationKey = resolveVerificationKey();
                if (!StringUtils.hasText(verificationKey)) {
                    log.warn("SendGrid signature validation is ENABLED but no verification key configured. " +
                            "Set sendgrid.webhook.verification-key. Proceeding without validation.");
                } else if (!isSignatureValid(requestBody, signature, timestamp, verificationKey)) {
                    log.warn("Invalid SendGrid signature. Rejecting webhook.");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
                }
            }

            // Parse JSON array of events
            JsonNode events;
            try {
                events = objectMapper.readTree(requestBody);
            } catch (IOException e) {
                log.error("Failed to parse SendGrid webhook JSON: {}", e.getMessage());
                return ResponseEntity.badRequest().body("Invalid JSON");
            }

            if (!events.isArray()) {
                log.error("SendGrid webhook body is not an array");
                return ResponseEntity.badRequest().body("Expected JSON array");
            }

            int processedEvents = 0;
            for (JsonNode event : events) {
                try {
                    processEmailEvent(event);
                    processedEvents++;
                } catch (Exception e) {
                    log.error("Error processing email event: {}", e.getMessage(), e);
                    // Continue processing other events
                }
            }

            log.info("Processed {}/{} SendGrid events", processedEvents, events.size());
            return ResponseEntity.ok("Processed " + processedEvents + " events");

        } catch (Exception e) {
            log.error("Error processing SendGrid webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    private void processEmailEvent(JsonNode event) {
        String eventType = getStringValue(event, "event");
        String messageId = getStringValue(event, "sg_message_id");
        String email = getStringValue(event, "email");

        // Extract timestamp (Unix timestamp)
        Long timestampLong = getLongValue(event, "timestamp");
        OffsetDateTime eventTime = timestampLong != null ?
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestampLong), ZoneOffset.UTC) :
                OffsetDateTime.now(ZoneOffset.UTC);

        log.info("Email event: {} for {} (MessageID: {})", eventType, email, messageId);

        if (!StringUtils.hasText(messageId) || !StringUtils.hasText(eventType)) {
            log.warn("Missing required fields: messageId={}, event={}", messageId, eventType);
            return;
        }

        // Find ReviewRequest by SendGrid message ID
        Optional<ReviewRequest> requestOpt = findReviewRequestByEmailMessageId(messageId);

        if (requestOpt.isEmpty()) {
            log.debug("No ReviewRequest found for SendGrid message ID: {}", messageId);
            return;
        }

        ReviewRequest reviewRequest = requestOpt.get();

        // Update status based on event type with monotonic progression
        updateReviewRequestStatusFromEmailEvent(reviewRequest, eventType, event, eventTime);

        // Save changes
        reviewRequestRepository.save(reviewRequest);
        log.info("Updated ReviewRequest {} status based on email event: {}",
                reviewRequest.getId(), eventType);
    }

    private void updateReviewRequestStatusFromEmailEvent(ReviewRequest rr, String eventType,
                                                         JsonNode event, OffsetDateTime eventTime) {
        ReviewRequest.RequestStatus current = rr.getStatus();
        if (current == null) current = ReviewRequest.RequestStatus.PENDING;

        String normalizedEvent = eventType.toLowerCase(Locale.ROOT);

        // Update email status regardless of request status
        rr.setEmailStatus(normalizedEvent);

        switch (normalizedEvent) {
            case "processed":
                // Email accepted by SendGrid
                if (current == ReviewRequest.RequestStatus.PENDING) {
                    rr.setStatus(ReviewRequest.RequestStatus.SENT);
                    if (rr.getSentAt() == null) {
                        rr.setSentAt(eventTime);
                    }
                }
                break;

            case "delivered":
                // Email delivered to recipient's server
                if (current == ReviewRequest.RequestStatus.PENDING ||
                        current == ReviewRequest.RequestStatus.SENT) {
                    rr.setStatus(ReviewRequest.RequestStatus.DELIVERED);
                    rr.setDeliveredAt(eventTime);
                    if (rr.getSentAt() == null) {
                        rr.setSentAt(eventTime);
                    }
                }
                break;

            case "open":
                // Email opened by recipient
                if (current != ReviewRequest.RequestStatus.CLICKED &&
                        current != ReviewRequest.RequestStatus.COMPLETED) {
                    rr.setStatus(ReviewRequest.RequestStatus.OPENED);
                    rr.setOpenedAt(eventTime);
                }
                break;

            case "click":
                // Link clicked in email
                if (current != ReviewRequest.RequestStatus.COMPLETED) {
                    rr.setStatus(ReviewRequest.RequestStatus.CLICKED);
                    rr.setClickedAt(eventTime);
                }
                break;

            case "bounce":
                // Hard bounce - mark as bounced only if no successful engagement
                if (current != ReviewRequest.RequestStatus.DELIVERED &&
                        current != ReviewRequest.RequestStatus.OPENED &&
                        current != ReviewRequest.RequestStatus.CLICKED &&
                        current != ReviewRequest.RequestStatus.COMPLETED) {
                    rr.setStatus(ReviewRequest.RequestStatus.BOUNCED);

                    String reason = getStringValue(event, "reason");
                    String bounceType = getStringValue(event, "type");
                    rr.setEmailErrorCode("BOUNCE_" + (bounceType != null ? bounceType.toUpperCase() : "UNKNOWN"));

                    if (StringUtils.hasText(reason)) {
                        rr.setErrorMessage("Email bounced: " + reason);
                    }
                }
                break;

            case "blocked":
            case "dropped":
                // SendGrid blocked/dropped the email
                if (current == ReviewRequest.RequestStatus.PENDING) {
                    rr.setStatus(ReviewRequest.RequestStatus.FAILED);

                    String reason = getStringValue(event, "reason");
                    rr.setEmailErrorCode(normalizedEvent.toUpperCase());

                    if (StringUtils.hasText(reason)) {
                        rr.setErrorMessage("Email " + eventType + ": " + reason);
                    }
                }
                break;

            case "deferred":
                // Temporary failure - don't change status, just log
                String reason = getStringValue(event, "reason");
                log.info("Email deferred for ReviewRequest {}: {}", rr.getId(), reason);
                break;

            case "unsubscribe":
            case "spamreport":
                // Compliance events - log but don't change request status
                log.info("Email compliance event for ReviewRequest {}: {}", rr.getId(), eventType);
                break;

            default:
                log.debug("Unhandled email event type: {}", eventType);
        }
    }

    private Optional<ReviewRequest> findReviewRequestByEmailMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return Optional.empty();
        }

        // Clean up SendGrid message ID (remove angle brackets if present)
        String cleanMessageId = messageId.replaceAll("[<>]", "").trim();

        log.debug("Searching for ReviewRequest with SendGrid message ID: {}", cleanMessageId);
        return reviewRequestRepository.findBySendgridMessageId(cleanMessageId);
    }

    private boolean isSignatureValid(String payload, String signature, String timestamp, String verificationKey) {
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(timestamp)) {
            log.warn("Missing signature or timestamp headers");
            return false;
        }

        try {
            // SendGrid signature validation
            // Signature is base64(hmac-sha256(timestamp + payload, verification_key))
            String signedPayload = timestamp + payload;

            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    verificationKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);

            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = Base64.getEncoder().encodeToString(hash);

            boolean valid = expectedSignature.equals(signature);
            if (!valid) {
                log.warn("Signature mismatch. Expected: {}, Got: {}", expectedSignature, signature);
            }
            return valid;

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error validating SendGrid signature: {}", e.getMessage());
            return false;
        }
    }

    private String resolveVerificationKey() {
        if (StringUtils.hasText(sendgridVerificationKey)) {
            return sendgridVerificationKey;
        }
        if (StringUtils.hasText(legacyWebhookSecret)) {
            return legacyWebhookSecret;
        }
        return null;
    }

    // Helper methods for safe JSON parsing
    private String getStringValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }

    private Long getLongValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asLong() : null;
    }

    private Integer getIntValue(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asInt() : null;
    }
}