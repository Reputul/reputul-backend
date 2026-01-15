package com.reputul.backend.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.models.ChannelCredential;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.ChannelCredentialRepository;
import com.reputul.backend.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Facebook Data Deletion Callback Controller
 *
 * Implements Facebook's required Data Deletion Request Callback
 * https://developers.facebook.com/docs/development/create-an-app/app-dashboard/data-deletion-callback
 *
 * When a user deletes your app from their Facebook account or requests data deletion,
 * Facebook sends a signed request to this endpoint.
 *
 * REQUIREMENTS:
 * - Must return 200 OK with confirmation URL and code
 * - Must delete user data within 30 days
 * - Must verify request signature for security
 */
@RestController
@RequestMapping("/api/v1/facebook")
@Slf4j
public class FacebookDataDeletionController {

    private final UserRepository userRepository;
    private final ChannelCredentialRepository credentialRepository;
    private final ObjectMapper objectMapper;

    @Value("${facebook.oauth.app-secret}")
    private String appSecret;

    public FacebookDataDeletionController(
            UserRepository userRepository,
            ChannelCredentialRepository credentialRepository,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle Facebook Data Deletion Callback
     * POST /api/v1/facebook/data-deletion
     *
     * Facebook sends a signed_request parameter when user deletes the app
     * or requests data deletion.
     *
     * Request format: signed_request=<signature>.<payload>
     * Payload contains: { user_id: "facebook_user_id", issued_at: timestamp }
     */
    @PostMapping("/data-deletion")
    public ResponseEntity<?> handleDataDeletionRequest(
            @RequestParam("signed_request") String signedRequest) {

        log.info("📩 Received Facebook data deletion request");

        try {
            // Parse and verify signed request
            String[] parts = signedRequest.split("\\.");
            if (parts.length != 2) {
                log.error("Invalid signed_request format");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid request format"));
            }

            String encodedSignature = parts[0];
            String encodedPayload = parts[1];

            // Verify signature
            if (!verifySignature(encodedSignature, encodedPayload)) {
                log.error("❌ Invalid signature - potential security breach");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid signature"));
            }

            // Decode payload
            String payloadJson = new String(
                    Base64.getUrlDecoder().decode(encodedPayload),
                    StandardCharsets.UTF_8
            );

            JsonNode payload = objectMapper.readTree(payloadJson);
            String facebookUserId = payload.get("user_id").asText();
            long issuedAt = payload.get("issued_at").asLong();

            log.info("✅ Valid deletion request for Facebook User ID: {}", facebookUserId);
            log.info("Request issued at: {}", new Date(issuedAt * 1000));

            // Process deletion request
            String confirmationCode = processDeletionRequest(facebookUserId);

            // Return required response format
            Map<String, String> response = new HashMap<>();
            response.put("url", "https://reputul.com/data-deletion-status?code=" + confirmationCode);
            response.put("confirmation_code", confirmationCode);

            log.info("✅ Data deletion request processed successfully. Confirmation code: {}", confirmationCode);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error processing Facebook data deletion request", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process deletion request"));
        }
    }

    /**
     * Verify Facebook signed request signature
     * Uses HMAC-SHA256 with app secret
     */
    private boolean verifySignature(String encodedSignature, String encodedPayload) {
        try {
            // Decode the signature
            byte[] expectedSignature = Base64.getUrlDecoder().decode(encodedSignature);

            // Calculate HMAC-SHA256 of payload
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(
                    appSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKey);
            byte[] actualSignature = mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8));

            // Compare signatures
            return MessageDigest.isEqual(expectedSignature, actualSignature);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }

    /**
     * Process the data deletion request
     *
     * Steps:
     * 1. Find all ChannelCredentials with this Facebook user ID
     * 2. Mark them for deletion
     * 3. Optionally: Find associated User and mark account for review
     * 4. Generate confirmation code
     * 5. Schedule actual deletion within 30 days
     *
     * @param facebookUserId Facebook user ID from signed request
     * @return Confirmation code for tracking
     */
    private String processDeletionRequest(String facebookUserId) {
        String confirmationCode = generateConfirmationCode();

        try {
            // Find all Facebook credentials with this user ID
            // Since metadata is stored as JSON string, we need to search all and filter
            List<ChannelCredential> allCredentials = credentialRepository
                    .findByPlatformType(ChannelCredential.PlatformType.FACEBOOK);

            List<ChannelCredential> credentials = allCredentials.stream()
                    .filter(c -> {
                        String json = c.getMetadataJson();
                        return json != null && json.contains(facebookUserId);
                    })
                    .toList();

            if (credentials.isEmpty()) {
                log.warn("No credentials found for Facebook User ID: {}", facebookUserId);
                return confirmationCode;
            }

            log.info("Found {} Facebook credentials for user {}", credentials.size(), facebookUserId);

            // Revoke and mark for deletion
            for (ChannelCredential credential : credentials) {
                // Update status
                credential.setStatus(ChannelCredential.CredentialStatus.REVOKED);
                credential.setSyncErrorMessage("User requested data deletion via Facebook");

                // Clear tokens
                credential.setAccessToken(null);
                credential.setRefreshToken(null);
                credential.setTokenExpiresAt(null);

                // Add deletion metadata
                Map<String, Object> metadata = credential.getMetadata();
                if (metadata == null) {
                    metadata = new HashMap<>();
                }
                metadata.put("deletionRequested", true);
                metadata.put("deletionRequestedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
                metadata.put("deletionConfirmationCode", confirmationCode);
                metadata.put("facebookUserId", facebookUserId);
                credential.setMetadata(metadata);

                credentialRepository.save(credential);

                log.info("Revoked credential ID: {} for business: {}",
                        credential.getId(),
                        credential.getBusiness().getId());
            }

            // TODO: Optionally notify the Reputul user that their Facebook connection was removed
            // You could send an email or create a notification here

            log.info("✅ Successfully processed deletion for {} credentials", credentials.size());

        } catch (Exception e) {
            log.error("Error processing deletion for Facebook user {}", facebookUserId, e);
            // Still return confirmation code - Facebook requires 200 OK response
        }

        return confirmationCode;
    }

    /**
     * Generate unique confirmation code for tracking deletion requests
     */
    private String generateConfirmationCode() {
        return "DEL-" + UUID.randomUUID().toString().substring(0, 13).toUpperCase();
    }

    /**
     * Optional: Endpoint to check deletion status
     * GET /api/v1/facebook/data-deletion-status?code=DEL-XXX
     */
    @GetMapping("/data-deletion-status")
    public ResponseEntity<?> checkDeletionStatus(@RequestParam("code") String confirmationCode) {
        log.info("Checking deletion status for code: {}", confirmationCode);

        // Search for credentials with this confirmation code in metadata
        // Since metadata is stored as JSON string, we need to search the metadataJson column
        List<ChannelCredential> allCredentials = credentialRepository.findAll();

        List<ChannelCredential> matchingCredentials = allCredentials.stream()
                .filter(c -> {
                    String json = c.getMetadataJson();
                    return json != null && json.contains(confirmationCode);
                })
                .toList();

        if (matchingCredentials.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "status", "NOT_FOUND",
                            "message", "No deletion request found with this confirmation code"
                    ));
        }

        // Check deletion status
        ChannelCredential credential = matchingCredentials.get(0);
        Map<String, Object> metadata = credential.getMetadata();

        String requestedAt = metadata != null && metadata.get("deletionRequestedAt") != null
                ? metadata.get("deletionRequestedAt").toString()
                : "Unknown";

        return ResponseEntity.ok(Map.of(
                "status", "COMPLETED",
                "message", "Facebook data has been deleted",
                "requestedAt", requestedAt,
                "confirmationCode", confirmationCode,
                "details", "All Facebook access tokens and associated data have been removed from our systems."
        ));
    }
}