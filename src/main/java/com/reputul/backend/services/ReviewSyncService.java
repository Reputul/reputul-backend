package com.reputul.backend.services;

import com.reputul.backend.exceptions.TokenExpiredException;
import com.reputul.backend.integrations.*;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReviewSyncService {

    private final ChannelCredentialRepository credentialRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewSyncJobRepository syncJobRepository;
    private final BusinessRepository businessRepository;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;

    public ReviewSyncService(
            ChannelCredentialRepository credentialRepository,
            ReviewRepository reviewRepository,
            ReviewSyncJobRepository syncJobRepository,
            BusinessRepository businessRepository,
            List<PlatformReviewClient> clients) {

        this.credentialRepository = credentialRepository;
        this.reviewRepository = reviewRepository;
        this.syncJobRepository = syncJobRepository;
        this.businessRepository = businessRepository;

        // Map platform types to their respective clients
        this.platformClients = clients.stream()
                .collect(Collectors.toMap(
                        PlatformReviewClient::getPlatformType,
                        client -> client));

        log.info("ReviewSyncService initialized with {} platform clients", platformClients.size());
    }

    /**
     * Sync reviews for a specific business from all connected platforms
     */
    @Transactional
    public List<ReviewSyncJob> syncBusinessReviews(Long businessId) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new IllegalArgumentException("Business not found: " + businessId));

        List<ChannelCredential> activeCredentials = credentialRepository
                .findByBusinessIdAndStatus(businessId, ChannelCredential.CredentialStatus.ACTIVE);

        log.info("Found {} active credentials for business {}", activeCredentials.size(), businessId);

        List<ReviewSyncJob> jobs = new ArrayList<>();

        for (ChannelCredential credential : activeCredentials) {
            try {
                ReviewSyncJob job = syncPlatformReviews(credential, business);
                jobs.add(job);
            } catch (Exception e) {
                log.error("Failed to sync reviews for business {} on platform {}",
                        businessId, credential.getPlatformType(), e);
            }
        }

        return jobs;
    }

    /**
     * Sync reviews from a single platform
     *
     * UPDATED: Includes automatic token refresh/extension before syncing
     */
    @Transactional
    public ReviewSyncJob syncPlatformReviews(ChannelCredential credential, Business business) {

        // Create sync job
        ReviewSyncJob job = ReviewSyncJob.builder()
                .credential(credential)
                .business(business)
                .platformType(credential.getPlatformType().name())
                .status(ReviewSyncJob.SyncStatus.PENDING)
                .build();
        job = syncJobRepository.save(job);

        try {
            // Mark as running
            job.setStatus(ReviewSyncJob.SyncStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
            job = syncJobRepository.save(job);

            // ========================================
            // UPDATED: Check token and refresh/extend if needed
            // ========================================
            credential = ensureValidToken(credential);

            // Get platform client
            PlatformReviewClient client = platformClients.get(credential.getPlatformType());
            if (client == null) {
                throw new PlatformIntegrationException(
                        "No client available for platform: " + credential.getPlatformType());
            }

            // Fetch reviews from platform (only since last sync)
            OffsetDateTime sinceDate = credential.getLastSyncAt();
            List<PlatformReviewDto> platformReviews = client.fetchReviews(credential, sinceDate);

            job.setReviewsFetched(platformReviews.size());
            log.info("Fetched {} reviews from {} for business {}",
                    platformReviews.size(), credential.getPlatformType(), business.getId());

            // Process each review
            int newCount = 0;
            int updatedCount = 0;
            int skippedCount = 0;

            for (PlatformReviewDto dto : platformReviews) {
                try {
                    ReviewSyncResult result = syncSingleReview(business, credential, dto);
                    switch (result) {
                        case CREATED:
                            newCount++;
                            break;
                        case UPDATED:
                            updatedCount++;
                            break;
                        case SKIPPED:
                            skippedCount++;
                            break;
                    }
                } catch (Exception e) {
                    log.error("Error syncing review {}", dto.getPlatformReviewId(), e);
                    skippedCount++;
                }
            }

            // Update job results
            job.setReviewsNew(newCount);
            job.setReviewsUpdated(updatedCount);
            job.setReviewsSkipped(skippedCount);
            job.setStatus(ReviewSyncJob.SyncStatus.COMPLETED);
            job.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));

            // Update credential last sync
            credential.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
            credential.setLastSyncStatus("SUCCESS");
            credential.setNextSyncScheduled(calculateNextSync());
            credentialRepository.save(credential);

            log.info("Sync completed for business {} on {}: {} new, {} updated, {} skipped",
                    business.getId(), credential.getPlatformType(), newCount, updatedCount, skippedCount);

        } catch (TokenExpiredException e) {
            // Token expired and cannot be refreshed - let it propagate to controller
            log.error("Token expired for credential {}: {}", credential.getId(), e.getMessage());
            job.setStatus(ReviewSyncJob.SyncStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));

            // Save the updated credential status
            credentialRepository.save(credential);
            syncJobRepository.save(job);

            // Re-throw to be caught by controller
            throw e;

        } catch (Exception e) {
            log.error("Sync job failed", e);
            job.setStatus(ReviewSyncJob.SyncStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
            job.setRetryCount(job.getRetryCount() + 1);

            // Update credential status
            credential.setLastSyncStatus("FAILED");
            credential.setSyncErrorMessage(e.getMessage());
            credentialRepository.save(credential);
        }

        return syncJobRepository.save(job);
    }

    /**
     * UPDATED: Ensure token is valid, refresh/extend if needed
     *
     * Strategy:
     * - Google: Auto-refresh using refresh token if expired or expiring soon (within 5 min)
     * - Facebook: Auto-extend if token expires within 7 days (proactive extension)
     *
     * @param credential The credential to check
     * @return Updated credential with fresh token
     * @throws TokenExpiredException if token cannot be refreshed/extended
     * @throws PlatformIntegrationException if platform client is unavailable or other errors occur
     */
    private ChannelCredential ensureValidToken(ChannelCredential credential)
            throws TokenExpiredException, PlatformIntegrationException {

        // Check if token needs refresh/extension
        if (!credential.needsRefresh()) {
            log.debug("Token for credential {} is still valid", credential.getId());
            return credential;
        }

        log.info("Token for credential {} needs refresh (expires at: {})",
                credential.getId(), credential.getTokenExpiresAt());

        // Get the platform client
        PlatformReviewClient client = platformClients.get(credential.getPlatformType());
        if (client == null) {
            throw new PlatformIntegrationException(
                    "No client available for platform: " + credential.getPlatformType());
        }

        try {
            // Platform-specific token refresh/extension logic
            if (credential.getPlatformType() == ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS) {
                // Google: Refresh token (uses refresh token to get new access token)
                log.info("Refreshing Google token for credential {}", credential.getId());
                credential = client.refreshToken(credential);

            } else if (credential.getPlatformType() == ChannelCredential.PlatformType.FACEBOOK) {
                // Facebook: Extend token (only if within 7 days of expiry)
                OffsetDateTime sevenDaysFromNow = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

                if (credential.getTokenExpiresAt() != null &&
                        credential.getTokenExpiresAt().isBefore(sevenDaysFromNow)) {
                    log.info("Extending Facebook token for credential {} (expires in <7 days)", credential.getId());
                    credential = client.refreshToken(credential);
                } else {
                    log.debug("Facebook token for credential {} not yet due for extension", credential.getId());
                    return credential;
                }
            }

            // Save updated credential
            credential = credentialRepository.save(credential);
            log.info("Successfully refreshed/extended token for credential {}", credential.getId());

            return credential;

        } catch (TokenExpiredException e) {
            // Token refresh/extension failed - credential needs reconnection
            log.error("Token refresh failed for credential {}: {}", credential.getId(), e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("Unexpected error during token refresh for credential {}", credential.getId(), e);
            throw new PlatformIntegrationException("Token refresh failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sync a single review - handle deduplication
     */
    @Transactional
    protected ReviewSyncResult syncSingleReview(
            Business business,
            ChannelCredential credential,
            PlatformReviewDto dto) {

        String source = credential.getPlatformType().name().toLowerCase();

        // Check if review already exists by source + sourceReviewId
        Optional<Review> existingOpt = reviewRepository
                .findByBusinessIdAndSourceAndSourceReviewId(
                        business.getId(), source, dto.getPlatformReviewId());

        if (existingOpt.isPresent()) {
            // Review exists - check if it needs updating
            Review existing = existingOpt.get();

            boolean needsUpdate = false;
            if (dto.getComment() != null && !dto.getComment().equals(existing.getComment())) {
                existing.setComment(dto.getComment());
                needsUpdate = true;
            }
            if (dto.getBusinessResponse() != null &&
                    !dto.getBusinessResponse().equals(existing.getPlatformResponse())) {
                existing.setPlatformResponse(dto.getBusinessResponse());
                existing.setPlatformResponseAt(dto.getBusinessResponseAt());
                needsUpdate = true;
            }

            if (needsUpdate) {
                existing.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
                reviewRepository.save(existing);
                log.debug("Updated review {} from {}", dto.getPlatformReviewId(), source);
                return ReviewSyncResult.UPDATED;
            } else {
                return ReviewSyncResult.SKIPPED;
            }
        } else {
            // New review - create it
            Review newReview = Review.builder()
                    .business(business)
                    .source(source)
                    .sourceReviewId(dto.getPlatformReviewId())
                    .sourceReviewUrl(dto.getReviewUrl())
                    .rating(dto.getRating())
                    .comment(dto.getComment())
                    .customerName(dto.getReviewerName())
                    .reviewerPhotoUrl(dto.getReviewerPhotoUrl())
                    .platformVerified(dto.getIsPlatformVerified())
                    .platformResponse(dto.getBusinessResponse())
                    .platformResponseAt(dto.getBusinessResponseAt())
                    .syncedAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            reviewRepository.save(newReview);
            log.debug("Created new review from {}: {}", source, dto.getPlatformReviewId());
            return ReviewSyncResult.CREATED;
        }
    }

    /**
     * Calculate next sync time (6 hours from now)
     */
    private OffsetDateTime calculateNextSync() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(6);
    }

    /**
     * Result of syncing a single review
     */
    enum ReviewSyncResult {
        CREATED, UPDATED, SKIPPED
    }
}