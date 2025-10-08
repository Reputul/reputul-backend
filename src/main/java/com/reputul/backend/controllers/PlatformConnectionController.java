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

            // Generate state token (store in session/redis for verification)
            String state = UUID.randomUUID().toString();
            String redirectUri = "https://app.reputul.com/platform-callback";

            String authUrl = client.getAuthorizationUrl(state, redirectUri);

            // Store pending credential
            ChannelCredential pendingCred = ChannelCredential.builder()
                    .organization(business.getOrganization())
                    .business(business)
                    .platformType(platform)
                    .status(ChannelCredential.CredentialStatus.PENDING)
                    .createdBy(user)
                    .build();

            // Store state in metadata for verification
            pendingCred.setMetadata(Map.of("state", state, "businessId", businessId));
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
}