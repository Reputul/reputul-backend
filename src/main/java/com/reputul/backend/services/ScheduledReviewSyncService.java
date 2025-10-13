package com.reputul.backend.services;

import com.reputul.backend.integrations.PlatformIntegrationException;
import com.reputul.backend.integrations.PlatformReviewClient;
import com.reputul.backend.models.ChannelCredential;
import com.reputul.backend.repositories.ChannelCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Automated background service for syncing reviews from connected platforms
 *
 * Features:
 * - Scheduled review sync every 6 hours
 * - Automatic token refresh before expiry
 * - Error recovery and retry logic
 * - Performance monitoring and metrics
 *
 * Configuration:
 * - Enable/disable via: automated-sync.enabled=true
 * - Schedule via cron: automated-sync.cron=0 (star)/6 * * * * (replace (star) with asterisk)
 */
@Service
@Slf4j
@ConditionalOnProperty(
        name = "automated-sync.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ScheduledReviewSyncService {

    private final ChannelCredentialRepository credentialRepository;
    private final ReviewSyncService reviewSyncService;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;

    public ScheduledReviewSyncService(
            ChannelCredentialRepository credentialRepository,
            ReviewSyncService reviewSyncService,
            List<PlatformReviewClient> clients) {

        this.credentialRepository = credentialRepository;
        this.reviewSyncService = reviewSyncService;
        this.platformClients = clients.stream()
                .collect(Collectors.toMap(
                        PlatformReviewClient::getPlatformType,
                        client -> client
                ));

        log.info("ScheduledReviewSyncService initialized with {} platform clients",
                platformClients.size());
    }

    /**
     * Scheduled review sync job - runs every 6 hours by default
     * Configurable via: automated-sync.cron
     */
    @Scheduled(cron = "${automated-sync.cron:0 */6 * * * *}")
    @Transactional
    public void syncAllActiveCredentials() {
        log.info("Starting scheduled review sync job");

        long startTime = System.currentTimeMillis();
        int totalSynced = 0;
        int totalErrors = 0;

        try {
            // Use the optimized repository query to find credentials due for sync
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            List<ChannelCredential> credentialsDueForSync = credentialRepository
                    .findDueForSync(now, ChannelCredential.CredentialStatus.ACTIVE);

            log.info("Found {} credentials due for sync", credentialsDueForSync.size());

            for (ChannelCredential credential : credentialsDueForSync) {
                try {
                    // Refresh token if needed
                    if (credential.needsRefresh()) {
                        credential = refreshCredentialToken(credential);
                    }

                    // Sync reviews
                    reviewSyncService.syncPlatformReviews(credential, credential.getBusiness());
                    totalSynced++;

                    log.debug("Successfully synced reviews for business {} on platform {}",
                            credential.getBusiness().getId(),
                            credential.getPlatformType());

                } catch (Exception e) {
                    totalErrors++;
                    log.error("Failed to sync reviews for credential {} ({}): {}",
                            credential.getId(),
                            credential.getPlatformType(),
                            e.getMessage());

                    // Update credential with error status
                    credential.setLastSyncStatus("FAILED");
                    credential.setSyncErrorMessage(e.getMessage());
                    credentialRepository.save(credential);
                }
            }

        } catch (Exception e) {
            log.error("Scheduled sync job failed", e);
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("Scheduled review sync completed: {} synced, {} errors, {} ms",
                totalSynced, totalErrors, duration);
    }

    /**
     * Scheduled token refresh job - runs daily at 2 AM
     * Refreshes tokens that will expire within 7 days
     */
    @Scheduled(cron = "${automated-sync.token-refresh-cron:0 0 2 * * *}")
    @Transactional
    public void refreshExpiringTokens() {
        log.info("Starting scheduled token refresh job");

        int refreshed = 0;
        int failed = 0;

        try {
            // Find credentials with tokens expiring within 7 days
            OffsetDateTime expiryThreshold = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

            // FIXED: Use findAll and filter, since findByStatus doesn't exist
            List<ChannelCredential> expiringCredentials = credentialRepository
                    .findAll()
                    .stream()
                    .filter(cred -> cred.getStatus() == ChannelCredential.CredentialStatus.ACTIVE)
                    .filter(cred -> cred.getTokenExpiresAt() != null
                            && cred.getTokenExpiresAt().isBefore(expiryThreshold))
                    .collect(Collectors.toList());

            log.info("Found {} credentials with tokens expiring within 7 days",
                    expiringCredentials.size());

            for (ChannelCredential credential : expiringCredentials) {
                try {
                    refreshCredentialToken(credential);
                    refreshed++;
                } catch (Exception e) {
                    failed++;
                    log.error("Failed to refresh token for credential {} ({}): {}",
                            credential.getId(),
                            credential.getPlatformType(),
                            e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Token refresh job failed", e);
        }

        log.info("Token refresh completed: {} refreshed, {} failed", refreshed, failed);
    }

    /**
     * Health check job - runs every hour
     * Validates active credentials and updates status
     */
    @Scheduled(cron = "${automated-sync.health-check-cron:0 0 * * * *}")
    @Transactional
    public void validateActiveCredentials() {
        log.debug("Starting credential health check");

        int healthy = 0;
        int unhealthy = 0;

        try {
            // FIXED: Use findAll and filter, since findByStatus doesn't exist
            List<ChannelCredential> activeCredentials = credentialRepository
                    .findAll()
                    .stream()
                    .filter(cred -> cred.getStatus() == ChannelCredential.CredentialStatus.ACTIVE)
                    .collect(Collectors.toList());

            for (ChannelCredential credential : activeCredentials) {
                try {
                    PlatformReviewClient client = platformClients.get(credential.getPlatformType());

                    if (client == null) {
                        log.warn("No client available for platform: {}", credential.getPlatformType());
                        continue;
                    }

                    boolean isValid = client.validateCredentials(credential);

                    if (isValid) {
                        healthy++;
                    } else {
                        unhealthy++;
                        credential.setStatus(ChannelCredential.CredentialStatus.ERROR);
                        credential.setSyncErrorMessage("Credential validation failed");
                        credentialRepository.save(credential);

                        log.warn("Credential {} validation failed", credential.getId());
                    }

                } catch (Exception e) {
                    unhealthy++;
                    log.warn("Error validating credential {}: {}",
                            credential.getId(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Health check job failed", e);
        }

        log.debug("Health check completed: {} healthy, {} unhealthy", healthy, unhealthy);
    }

    // ============ Helper Methods ============

    /**
     * Refresh credential token if needed
     */
    private ChannelCredential refreshCredentialToken(ChannelCredential credential)
            throws PlatformIntegrationException {

        log.info("Refreshing token for credential {} ({})",
                credential.getId(), credential.getPlatformType());

        PlatformReviewClient client = platformClients.get(credential.getPlatformType());

        if (client == null) {
            throw new PlatformIntegrationException(
                    "No client available for platform: " + credential.getPlatformType()
            );
        }

        try {
            ChannelCredential refreshedCredential = client.refreshToken(credential);
            credentialRepository.save(refreshedCredential);

            log.info("Successfully refreshed token for credential {}", credential.getId());
            return refreshedCredential;

        } catch (PlatformIntegrationException e) {
            // If refresh fails, mark credential as expired
            credential.setStatus(ChannelCredential.CredentialStatus.EXPIRED);
            credential.setSyncErrorMessage("Token refresh failed: " + e.getMessage());
            credentialRepository.save(credential);

            log.error("Token refresh failed for credential {}: {}",
                    credential.getId(), e.getMessage());

            throw e;
        }
    }
}