package com.reputul.backend.services;

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