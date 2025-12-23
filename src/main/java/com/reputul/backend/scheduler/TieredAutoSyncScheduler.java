package com.reputul.backend.scheduler;

import com.reputul.backend.models.ChannelCredential;
import com.reputul.backend.repositories.ChannelCredentialRepository;
import com.reputul.backend.services.ReviewSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiered Auto-Sync Scheduler
 *
 * Matches TrueReview's architecture:
 * - OAuth connections: Fast sync (every 15 mins)
 * - URL connections: Slow sync (every 2 hours)
 *
 * This creates upgrade incentive: OAuth users get near real-time updates!
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TieredAutoSyncScheduler {

    private final ChannelCredentialRepository channelCredentialRepository;
    private final ReviewSyncService reviewSyncService;

    // Track currently syncing platforms (prevent duplicates)
    private final Set<Long> currentlySyncing = ConcurrentHashMap.newKeySet();

    /**
     * FAST SYNC - OAuth Connections Only
     *
     * Runs every 15 minutes
     * Syncs OAuth-connected platforms for near real-time updates
     *
     * This is the "premium" tier - OAuth users get fast updates!
     */
    @Scheduled(cron = "0 */15 * * * *") // Every 15 minutes
    public void fastSyncOAuthConnections() {
        log.info("Starting FAST sync for OAuth connections");

        try {
            // Find OAuth connections only (ACTIVE status with connection_method = 'OAUTH')
            List<ChannelCredential> oauthCredentials = channelCredentialRepository
                    .findByStatusAndConnectionMethod(
                            ChannelCredential.CredentialStatus.ACTIVE,
                            "OAUTH"
                    );

            log.info("Found {} OAuth connections for fast sync", oauthCredentials.size());

            int synced = 0;
            int skipped = 0;

            for (ChannelCredential credential : oauthCredentials) {
                // Cache check: Skip if synced in last 15 minutes
                if (shouldSkipSync(credential, 15)) {
                    skipped++;
                    continue;
                }

                if (!currentlySyncing.contains(credential.getId())) {
                    if (syncPlatformWithCache(credential)) {
                        synced++;
                    }
                }
            }

            log.info("Fast sync completed: {} synced, {} skipped (cached)",
                    synced, skipped);

        } catch (Exception e) {
            log.error("Fast sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * SLOW SYNC - URL Connections Only
     *
     * Runs every 2 hours
     * Syncs URL-based connections (web scraping)
     *
     * Intentionally slower to:
     * 1. Reduce scraping load
     * 2. Create upgrade incentive (OAuth is faster!)
     * 3. Stay under rate limits
     */
    @Scheduled(cron = "0 0 */2 * * *") // Every 2 hours
    public void slowSyncUrlConnections() {
        log.info("Starting SLOW sync for URL connections");

        try {
            // Find URL connections only (ACTIVE status with connection_method = 'URL')
            List<ChannelCredential> urlCredentials = channelCredentialRepository
                    .findByStatusAndConnectionMethod(
                            ChannelCredential.CredentialStatus.ACTIVE,
                            "URL"
                    );

            log.info("Found {} URL connections for slow sync", urlCredentials.size());

            int synced = 0;
            int skipped = 0;

            for (ChannelCredential credential : urlCredentials) {
                // Cache check: Skip if synced in last 2 hours
                if (shouldSkipSync(credential, 120)) {
                    skipped++;
                    continue;
                }

                if (!currentlySyncing.contains(credential.getId())) {
                    if (syncPlatformWithCache(credential)) {
                        synced++;
                    }
                }
            }

            log.info("Slow sync completed: {} synced, {} skipped (cached)",
                    synced, skipped);

        } catch (Exception e) {
            log.error("Slow sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * BACKUP SYNC - All Connections
     *
     * Runs hourly as backup (like TrueReview's "hourly check")
     * Catches any missed webhooks or errors
     *
     * This is TrueReview's safety net: "We also run an hourly check,
     * so if a notification is missed, the review will appear within
     * the next hour"
     */
    @Scheduled(cron = "0 0 * * * *") // Every hour at :00
    public void backupSyncAll() {
        log.info("Starting BACKUP sync (hourly safety net)");

        try {
            // Get all ACTIVE credentials
            List<ChannelCredential> allCredentials = channelCredentialRepository
                    .findByStatus(ChannelCredential.CredentialStatus.ACTIVE);

            log.info("Found {} total platforms for backup sync", allCredentials.size());

            int synced = 0;
            int skipped = 0;

            for (ChannelCredential credential : allCredentials) {
                // Determine interval based on connection method
                String connectionMethod = credential.getConnectionMethod();
                int minInterval = "OAUTH".equals(connectionMethod) ? 15 : 120;

                if (shouldSkipSync(credential, minInterval)) {
                    skipped++;
                    continue;
                }

                if (!currentlySyncing.contains(credential.getId())) {
                    if (syncPlatformWithCache(credential)) {
                        synced++;
                    }
                }
            }

            log.info("Backup sync completed: {} synced, {} skipped (cached)",
                    synced, skipped);

        } catch (Exception e) {
            log.error("Backup sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if sync should be skipped based on cache
     */
    private boolean shouldSkipSync(ChannelCredential credential, int minIntervalMinutes) {
        if (credential.getLastSyncAt() == null) {
            return false; // Never synced
        }

        Duration timeSinceSync = Duration.between(
                credential.getLastSyncAt(),
                OffsetDateTime.now()
        );

        long minutesSinceSync = timeSinceSync.toMinutes();

        if (minutesSinceSync < minIntervalMinutes) {
            log.debug("Skipping sync for {} - last synced {} mins ago (cache hit)",
                    credential.getId(), minutesSinceSync);
            return true;
        }

        return false;
    }

    /**
     * Sync platform with duplicate prevention
     */
    private boolean syncPlatformWithCache(ChannelCredential credential) {
        if (!currentlySyncing.add(credential.getId())) {
            log.warn("Platform {} already syncing", credential.getId());
            return false;
        }

        try {
            String method = credential.getConnectionMethod();
            String platform = credential.getPlatformType().name();

            log.info("Syncing {} platform: {} (ID: {})",
                    method, platform, credential.getId());

            // Perform sync using your existing service
            reviewSyncService.syncPlatformReviews(credential, credential.getBusiness());

            // Update last sync time
            credential.setLastSyncAt(OffsetDateTime.now());
            credential.setLastSyncStatus("SUCCESS");
            credential.setSyncErrorMessage(null);
            channelCredentialRepository.save(credential);

            log.info("Successfully synced {} platform: {}", method, platform);
            return true;

        } catch (Exception e) {
            log.error("Failed to sync platform {}: {}",
                    credential.getId(), e.getMessage());

            // Update error status
            credential.setLastSyncStatus("ERROR");
            credential.setSyncErrorMessage(e.getMessage());

            // Check if it's an auth error
            if (isAuthenticationError(e)) {
                credential.setStatus(ChannelCredential.CredentialStatus.EXPIRED);
                log.warn("Platform {} marked as EXPIRED", credential.getPlatformType());
            }

            channelCredentialRepository.save(credential);
            return false;

        } finally {
            currentlySyncing.remove(credential.getId());
        }
    }

    /**
     * Check if exception is authentication-related
     */
    private boolean isAuthenticationError(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return message.contains("unauthorized") ||
                message.contains("forbidden") ||
                message.contains("invalid token") ||
                message.contains("token expired") ||
                message.contains("401") ||
                message.contains("403");
    }
}