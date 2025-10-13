package com.reputul.backend.controllers;

import com.reputul.backend.integrations.*;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import com.reputul.backend.services.ReviewSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for managing platform OAuth connections and review syncing
 * Supports: Google My Business, Facebook, Yelp
 */
@RestController
@RequestMapping("/api/v1/platforms")
@Slf4j
public class PlatformConnectionController {

    private final ChannelCredentialRepository credentialRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final ReviewSyncService reviewSyncService;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;

    @Value("${google.oauth.redirect-uri}")
    private String googleRedirectUri;

    @Value("${facebook.oauth.redirect-uri}")
    private String facebookRedirectUri;

    @Value("${yelp.oauth.redirect-uri:${app.frontend.url}/oauth/callback/yelp}")
    private String yelpRedirectUri;

    public PlatformConnectionController(
            ChannelCredentialRepository credentialRepository,
            BusinessRepository businessRepository,
            UserRepository userRepository,
            ReviewSyncService reviewSyncService,
            List<PlatformReviewClient> clients) {

        this.credentialRepository = credentialRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.reviewSyncService = reviewSyncService;
        this.platformClients = clients.stream()
                .collect(Collectors.toMap(
                        PlatformReviewClient::getPlatformType,
                        c -> c));

        log.info("PlatformConnectionController initialized with {} platform clients", platformClients.size());
    }

    /**
     * Get OAuth authorization URL for connecting a platform
     *
     * @param platformType Platform to connect (GOOGLE_MY_BUSINESS, FACEBOOK, etc.)
     * @param businessId Business to connect platform to
     * @param authentication Current user authentication
     * @return JSON with authorization URL
     */
    @GetMapping("/connect/{platformType}")
    public ResponseEntity<?> getConnectionUrl(
            @PathVariable String platformType,
            @RequestParam Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership - check if business belongs to user's organization
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized to connect platforms for this business"));
            }

            ChannelCredential.PlatformType platform = parsePlatformType(platformType);

            PlatformReviewClient client = platformClients.get(platform);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Platform not supported: " + platformType));
            }

            // Generate state token for CSRF protection
            String state = UUID.randomUUID().toString();
            String redirectUri = getRedirectUri(platform);

            String authUrl = client.getAuthorizationUrl(state, redirectUri);

            // CRITICAL FIX: Delete old credential if exists, then create fresh one
            // This avoids JPA dirty-checking issues when reusing entities
            Optional<ChannelCredential> existingCred = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, platform);

            if (existingCred.isPresent()) {
                log.info("Deleting old {} credential {} before creating new one",
                        platform, existingCred.get().getId());
                credentialRepository.delete(existingCred.get());
                credentialRepository.flush(); // Ensure delete completes before insert
            }

            // Always create fresh credential (avoid JPA entity reuse issues)
            ChannelCredential pendingCred = ChannelCredential.builder()
                    .organization(business.getOrganization())
                    .business(business)
                    .platformType(platform)
                    .status(ChannelCredential.CredentialStatus.PENDING)
                    .createdBy(user)
                    .build();

            // Set metadataJson directly to ensure it persists
            Map<String, Object> metadata = Map.of("state", state, "businessId", businessId);
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String metadataJson = mapper.writeValueAsString(metadata);
                pendingCred.setMetadataJson(metadataJson);
                pendingCred.setMetadata(metadata); // Also set transient field for in-memory use
            } catch (Exception e) {
                log.error("Failed to serialize metadata", e);
                throw new RuntimeException("Failed to serialize metadata");
            }

            log.info("Created new {} credential for business {} (state: {})",
                    platform, businessId, state);

            // Save and flush to ensure immediate persistence
            credentialRepository.saveAndFlush(pendingCred);

            log.info("Saved credential ID {} with metadataJson: {}",
                    pendingCred.getId(), pendingCred.getMetadataJson());

            return ResponseEntity.ok(Map.of(
                    "authUrl", authUrl,
                    "state", state,
                    "platform", platform.name()
            ));

        } catch (Exception e) {
            log.error("Error generating connection URL for {}", platformType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle OAuth callback for Google My Business
     *
     * @param callbackData Code and state from OAuth provider
     * @param authentication Current user authentication
     * @return Success/error response
     */
    @PostMapping("/callback/google")
    public ResponseEntity<?> handleGoogleCallback(
            @RequestBody Map<String, String> callbackData,
            Authentication authentication) {

        return handleOAuthCallback(
                callbackData,
                authentication,
                ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS,
                googleRedirectUri
        );
    }

    /**
     * Handle OAuth callback for Facebook
     *
     * @param callbackData Code and state from OAuth provider
     * @param authentication Current user authentication
     * @return Success/error response
     */
    @PostMapping("/callback/facebook")
    public ResponseEntity<?> handleFacebookCallback(
            @RequestBody Map<String, String> callbackData,
            Authentication authentication) {

        return handleOAuthCallback(
                callbackData,
                authentication,
                ChannelCredential.PlatformType.FACEBOOK,
                facebookRedirectUri
        );
    }

    /**
     * Generic OAuth callback handler for all platforms
     * Handles token exchange and credential activation
     */
    private ResponseEntity<?> handleOAuthCallback(
            Map<String, String> callbackData,
            Authentication authentication,
            ChannelCredential.PlatformType platformType,
            String redirectUri) {

        try {
            String code = callbackData.get("code");
            String state = callbackData.get("state");

            log.info("Received {} OAuth callback - code: {}, state: {}",
                    platformType, code != null ? "present" : "missing", state);

            if (code == null || state == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Missing code or state"));
            }

            // Find pending credential by state token
            Optional<ChannelCredential> credOpt = credentialRepository.findByMetadataContaining(state);

            if (!credOpt.isPresent()) {
                log.error("No credential found for state: {}", state);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid state - credential not found. Please try connecting again."));
            }

            ChannelCredential credential = credOpt.get();

            // CRITICAL FIX: Verify the credential is still PENDING (not an old revoked one)
            if (credential.getStatus() != ChannelCredential.CredentialStatus.PENDING) {
                log.error("Credential found but status is {} (expected PENDING)", credential.getStatus());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid credential state. Please try connecting again."));
            }

            // Verify the credential belongs to the authenticated user's organization
            User user = getUserFromAuth(authentication);
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Not authorized"));
            }

            // CRITICAL FIX: Verify platform type matches
            if (!credential.getPlatformType().equals(platformType)) {
                log.error("Platform type mismatch: expected {}, got {}",
                        credential.getPlatformType(), platformType);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform type mismatch. Please try connecting again."));
            }

            // CRITICAL FIX: Verify businessId from metadata matches the credential's business
            Map<String, Object> metadata = credential.getMetadata();
            if (metadata != null && metadata.containsKey("businessId")) {
                Long metadataBusinessId = ((Number) metadata.get("businessId")).longValue();
                if (!metadataBusinessId.equals(credential.getBusiness().getId())) {
                    log.error("Business ID mismatch: metadata has {}, credential has {}",
                            metadataBusinessId, credential.getBusiness().getId());
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "error", "Business mismatch. Please try connecting again."));
                }
            }

            PlatformReviewClient client = platformClients.get(platformType);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform not supported"));
            }

            // Exchange authorization code for access token
            log.info("Exchanging code for {} access token", platformType);
            OAuthTokenResponse tokenResponse = client.exchangeCodeForToken(code, redirectUri);

            // Update credential with tokens
            credential.setAccessToken(tokenResponse.getAccessToken());
            credential.setRefreshToken(tokenResponse.getRefreshToken());

            if (tokenResponse.getExpiresIn() != null) {
                credential.setTokenExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                        .plusSeconds(tokenResponse.getExpiresIn()));
            }

            credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
            credential.setNextSyncScheduled(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

            credentialRepository.save(credential);

            log.info("Platform {} connected successfully for business {}, credential ID: {}",
                    platformType, credential.getBusiness().getId(), credential.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", platformType.name() + " connected successfully",
                    "credentialId", credential.getId()
            ));

        } catch (PlatformIntegrationException e) {
            log.error("{} OAuth callback error", platformType, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("{} OAuth callback unexpected error", platformType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Connection failed: " + e.getMessage()));
        }
    }

    /**
     * Get all connected platforms for a business
     *
     * @param businessId Business ID
     * @param authentication Current user
     * @return List of connected platforms with status
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<?> getConnectedPlatforms(
            @PathVariable Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            List<ChannelCredential> credentials = credentialRepository
                    .findByBusinessId(businessId);

            List<Map<String, Object>> platforms = credentials.stream()
                    .map(cred -> {
                        Map<String, Object> platformData = new HashMap<>();
                        platformData.put("id", cred.getId());
                        platformData.put("platform", cred.getPlatformType().name());
                        platformData.put("status", cred.getStatus().name());
                        platformData.put("lastSyncAt", cred.getLastSyncAt() != null ?
                                cred.getLastSyncAt().toString() : null);
                        platformData.put("lastSyncStatus", cred.getLastSyncStatus() != null ?
                                cred.getLastSyncStatus() : "NEVER_SYNCED");
                        platformData.put("tokenExpired", cred.isTokenExpired());
                        platformData.put("needsRefresh", cred.needsRefresh());

                        // Include page info for Facebook
                        if (cred.getPlatformType() == ChannelCredential.PlatformType.FACEBOOK) {
                            Map<String, Object> metadata = cred.getMetadata();
                            if (metadata != null) {
                                platformData.put("pageName", metadata.get("pageName"));
                                platformData.put("pageId", metadata.get("pageId"));
                            }
                        }

                        return platformData;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(platforms);

        } catch (Exception e) {
            log.error("Error fetching connected platforms", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manually trigger sync for a platform
     *
     * @param credentialId Credential ID to sync
     * @param authentication Current user
     * @return Sync job results
     */
    @PostMapping("/{credentialId}/sync")
    public ResponseEntity<?> triggerSync(
            @PathVariable Long credentialId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            ChannelCredential credential = credentialRepository.findById(credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("Credential not found"));

            // Verify ownership
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            // Check if token needs refresh
            if (credential.needsRefresh()) {
                log.info("Token needs refresh, attempting to refresh before sync");
                PlatformReviewClient client = platformClients.get(credential.getPlatformType());
                if (client != null) {
                    try {
                        credential = client.refreshToken(credential);
                        credentialRepository.save(credential);
                    } catch (PlatformIntegrationException e) {
                        log.warn("Token refresh failed: {}", e.getMessage());
                        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(Map.of("error", "Token expired. Please reconnect the platform."));
                    }
                }
            }

            ReviewSyncJob job = reviewSyncService.syncPlatformReviews(
                    credential, credential.getBusiness());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "jobId", job.getId(),
                    "status", job.getStatus(),
                    "reviewsFetched", job.getReviewsFetched(),
                    "reviewsNew", job.getReviewsNew(),
                    "reviewsUpdated", job.getReviewsUpdated()
            ));

        } catch (Exception e) {
            log.error("Error triggering sync", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Disconnect a platform
     *
     * @param credentialId Credential ID to disconnect
     * @param authentication Current user
     * @return Success response
     */
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<?> disconnectPlatform(
            @PathVariable Long credentialId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            ChannelCredential credential = credentialRepository.findById(credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("Credential not found"));

            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            log.info("Disconnecting platform {} for business {}",
                    credential.getPlatformType(), credential.getBusiness().getId());

            // CHANGED: Delete the credential instead of marking as REVOKED
            // This prevents finding old credentials during reconnection
            credentialRepository.delete(credential);

            return ResponseEntity.ok(Map.of("success", true));

        } catch (Exception e) {
            log.error("Error disconnecting platform", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ============ Helper Methods ============

    private User getUserFromAuth(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private ChannelCredential.PlatformType parsePlatformType(String platformType) {
        try {
            return ChannelCredential.PlatformType.valueOf(platformType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid platform type: " + platformType);
        }
    }

    private String getRedirectUri(ChannelCredential.PlatformType platform) {
        switch (platform) {
            case GOOGLE_MY_BUSINESS:
                return googleRedirectUri;
            case FACEBOOK:
                return facebookRedirectUri;
            case YELP:
                return yelpRedirectUri;
            default:
                log.warn("Unknown platform type: {}, using default redirect URI", platform);
                return googleRedirectUri; // Fallback to Google URI
        }
    }
}