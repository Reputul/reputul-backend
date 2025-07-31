package com.reputul.backend.controllers;

import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.repositories.ReviewRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Webhook controller for handling SMS delivery status updates from Twilio
 */
@RestController
@RequestMapping("/api/webhooks/sms")
@RequiredArgsConstructor
@Slf4j
public class SmsWebhookController {

    private final ReviewRequestRepository reviewRequestRepository;

    @Value("${sms.webhook.secret:}")
    private String webhookSecret;

    /**
     * Handle SMS status updates from Twilio
     * Configure this URL in your Twilio console: {your-domain}/api/webhooks/sms/status
     */
    @PostMapping("/status")
    public ResponseEntity<String> handleSmsStatusUpdate(
            @RequestParam Map<String, String> params,
            @RequestHeader(value = "X-Twilio-Signature", required = false) String twilioSignature) {

        try {
            // Extract relevant parameters from Twilio webhook
            String messageSid = params.get("MessageSid");
            String messageStatus = params.get("MessageStatus");
            String to = params.get("To");
            String from = params.get("From");
            String errorCode = params.get("ErrorCode");
            String errorMessage = params.get("ErrorMessage");

            log.info("📱 Received SMS status webhook - SID: {}, Status: {}, To: {}",
                    messageSid, messageStatus, maskPhoneNumber(to));

            if (messageSid == null || messageStatus == null) {
                log.warn("Invalid webhook payload - missing MessageSid or MessageStatus");
                return ResponseEntity.badRequest().body("Missing required parameters");
            }

            // Find the review request by SMS message ID
            Optional<ReviewRequest> reviewRequestOpt = reviewRequestRepository
                    .findBySmsMessageId(messageSid);

            if (reviewRequestOpt.isEmpty()) {
                log.warn("No review request found for SMS SID: {}", messageSid);
                return ResponseEntity.ok("Message not found");
            }

            ReviewRequest reviewRequest = reviewRequestOpt.get();

            // Update SMS status
            reviewRequest.setSmsStatus(messageStatus);
            if (errorCode != null) {
                reviewRequest.setSmsErrorCode(errorCode);
            }

            // Update review request status based on SMS status
            updateReviewRequestStatus(reviewRequest, messageStatus, errorMessage);

            // Save updates
            reviewRequestRepository.save(reviewRequest);

            log.info("✅ Updated review request {} with SMS status: {}",
                    reviewRequest.getId(), messageStatus);

            return ResponseEntity.ok("Status updated");

        } catch (Exception e) {
            log.error("❌ Error processing SMS status webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    /**
     * Handle SMS delivery receipts (optional, for detailed tracking)
     */
    @PostMapping("/delivery")
    public ResponseEntity<String> handleSmsDeliveryReceipt(
            @RequestParam Map<String, String> params) {

        try {
            String messageSid = params.get("MessageSid");
            String deliveryStatus = params.get("MessageStatus");

            log.info("📬 Received SMS delivery receipt - SID: {}, Status: {}",
                    messageSid, deliveryStatus);

            // Update delivery status if needed
            reviewRequestRepository.findBySmsMessageId(messageSid)
                    .ifPresent(request -> {
                        request.setSmsStatus(deliveryStatus);
                        if ("delivered".equals(deliveryStatus)) {
                            request.setStatus(ReviewRequest.RequestStatus.DELIVERED);
                        }
                        reviewRequestRepository.save(request);
                    });

            return ResponseEntity.ok("Delivery receipt processed");

        } catch (Exception e) {
            log.error("❌ Error processing SMS delivery receipt: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error processing delivery receipt");
        }
    }

    /**
     * Update review request status based on SMS delivery status
     */
    private void updateReviewRequestStatus(ReviewRequest reviewRequest,
                                           String smsStatus, String errorMessage) {
        switch (smsStatus.toLowerCase()) {
            case "sent":
                if (reviewRequest.getStatus() == ReviewRequest.RequestStatus.PENDING) {
                    reviewRequest.setStatus(ReviewRequest.RequestStatus.SENT);
                    if (reviewRequest.getSentAt() == null) {
                        reviewRequest.setSentAt(LocalDateTime.now());
                    }
                }
                break;

            case "delivered":
                reviewRequest.setStatus(ReviewRequest.RequestStatus.DELIVERED);
                break;

            case "failed":
            case "undelivered":
                reviewRequest.setStatus(ReviewRequest.RequestStatus.FAILED);
                if (errorMessage != null) {
                    reviewRequest.setErrorMessage("SMS failed: " + errorMessage);
                }
                break;

            case "read":
                // Some carriers provide read receipts
                reviewRequest.setOpenedAt(LocalDateTime.now());
                if (reviewRequest.getStatus() != ReviewRequest.RequestStatus.CLICKED &&
                        reviewRequest.getStatus() != ReviewRequest.RequestStatus.COMPLETED) {
                    reviewRequest.setStatus(ReviewRequest.RequestStatus.OPENED);
                }
                break;

            default:
                log.debug("Unhandled SMS status: {} for request {}", smsStatus, reviewRequest.getId());
        }
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
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-webhook",
                "timestamp", LocalDateTime.now()
        ));
    }

    /**
     * Get webhook configuration info (for debugging)
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getWebhookConfig() {
        return ResponseEntity.ok(Map.of(
                "statusUrl", "/api/webhooks/sms/status",
                "deliveryUrl", "/api/webhooks/sms/delivery",
                "webhookSecretConfigured", webhookSecret != null && !webhookSecret.trim().isEmpty(),
                "supportedEvents", new String[]{"sent", "delivered", "failed", "undelivered", "read"}
        ));
    }
}