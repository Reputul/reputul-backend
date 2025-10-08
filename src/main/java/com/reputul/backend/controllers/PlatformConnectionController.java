package com.reputul.backend.controllers;

import com.reputul.backend.integrations.*;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import com.reputul.backend.services.ReviewSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/platforms")
@Slf4j
public class PlatformConnectionController {

    private final ChannelCredentialRepository credentialRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final ReviewSyncService reviewSyncService;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;

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

            // Verify ownership
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            ChannelCredential.PlatformType platform =
                    ChannelCredential.PlatformType.valueOf(platformType.toUpperCase());

            PlatformReviewClient client = platformClients.get(platform);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Platform not supported: " + platformType));
            }

            // Generate state token
            String state = UUID.randomUUID().toString();
            String redirectUri = "http://localhost:3000/oauth/callback/google";

            String authUrl = client.getAuthorizationUrl(state, redirectUri);

            // Check if credential already exists
            Optional<ChannelCredential> existingCred = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, platform);

            ChannelCredential pendingCred;
            if (existingCred.isPresent()) {
                // Reuse existing credential, update state
                pendingCred = existingCred.get();
                pendingCred.setStatus(ChannelCredential.CredentialStatus.PENDING);
                pendingCred.setMetadata(Map.of("state", state, "businessId", businessId));
            } else {
                // Create new credential
                pendingCred = ChannelCredential.builder()
                        .organization(business.getOrganization())
                        .business(business)
                        .platformType(platform)
                        .status(ChannelCredential.CredentialStatus.PENDING)
                        .createdBy(user)
                        .build();
                pendingCred.setMetadata(Map.of("state", state, "businessId", businessId));
            }

            credentialRepository.save(pendingCred);

            log.info("Generated OAuth URL for {} platform, business {}", platformType, businessId);

            return ResponseEntity.ok(Map.of(
                    "authUrl", authUrl,
                    "state", state,
                    "platform", platformType
            ));

        } catch (Exception e) {
            log.error("Error generating connection URL", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List all connected platforms for a business
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<?> getConnectedPlatforms(
            @PathVariable Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

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

            ReviewSyncJob job = reviewSyncService.syncPlatformReviews(
                    credential, credential.getBusiness());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "jobId", job.getId(),
                    "status", job.getStatus(),
                    "reviewsFetched", job.getReviewsFetched(),
                    "reviewsNew", job.getReviewsNew()
            ));

        } catch (Exception e) {
            log.error("Error triggering sync", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Disconnect a platform
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

            credential.setStatus(ChannelCredential.CredentialStatus.REVOKED);
            credentialRepository.save(credential);

            log.info("Disconnected platform {} for business {}",
                    credential.getPlatformType(), credential.getBusiness().getId());

            return ResponseEntity.ok(Map.of("success", true));

        } catch (Exception e) {
            log.error("Error disconnecting platform", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private User getUserFromAuth(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    /**
     * Handle OAuth callback after user authorizes
     */
    @PostMapping("/callback/google")
    public ResponseEntity<?> handleOAuthCallback(
            @RequestBody Map<String, String> callbackData,
            Authentication authentication) {

        try {
            String code = callbackData.get("code");
            String state = callbackData.get("state");

            log.info("Received OAuth callback - code: {}, state: {}",
                    code != null ? "present" : "missing", state);

            if (code == null || state == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Missing code or state"));
            }

            // DEBUG: Log what we're searching for
            log.info("Searching for credential with state: {}", state);

            // Find pending credential by state
            Optional<ChannelCredential> credOpt = credentialRepository.findByMetadataContaining(state);

            // DEBUG: Log search result
            log.info("Credential found: {}", credOpt.isPresent());

            if (!credOpt.isPresent()) {
                // DEBUG: Show what's in the database
                List<ChannelCredential> allCreds = credentialRepository.findAll();
                log.error("No credential found for state: {}. Total credentials in DB: {}", state, allCreds.size());
                for (ChannelCredential c : allCreds) {
                    log.error("Credential ID {}: metadata = {}", c.getId(), c.getMetadata());
                }
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid state - credential not found"));
            }

            ChannelCredential credential = credOpt.get();

            // Verify the credential belongs to the authenticated user
            User user = getUserFromAuth(authentication);
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Not authorized"));
            }

            PlatformReviewClient client = platformClients.get(credential.getPlatformType());
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform not supported"));
            }

            // Exchange code for token
            OAuthTokenResponse tokenResponse = client.exchangeCodeForToken(
                    code, "http://localhost:3000/oauth/callback/google");

            // Update credential with tokens
            credential.setAccessToken(tokenResponse.getAccessToken());
            credential.setRefreshToken(tokenResponse.getRefreshToken());
            credential.setTokenExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                    .plusSeconds(tokenResponse.getExpiresIn()));
            credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
            credential.setNextSyncScheduled(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

            credentialRepository.save(credential);

            log.info("Platform Google connected successfully for business {}",
                    credential.getBusiness().getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Platform connected successfully",
                    "credentialId", credential.getId()
            ));

        } catch (Exception e) {
            log.error("OAuth callback error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }
}