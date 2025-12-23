package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Enhanced Reputation Service with Wilson Score Algorithm
 * FIXED: Uses weighted average for star ratings instead of binary positive rate
 */
@Service
@Slf4j
public class ReputationService {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;
    private final BadgeService badgeService;

    // Wilson Score constants
    private static final double WILSON_CONFIDENCE = 1.96; // 95% confidence interval
    private static final double RECENCY_LAMBDA = 0.00385; // ~180 day half-life: ln(2)/180

    // Quality scoring constants
    private static final double QUALITY_WEIGHT = 0.60;
    private static final double VELOCITY_WEIGHT = 0.25;
    private static final double RESPONSIVENESS_WEIGHT = 0.15;
    private static final int MIN_REVIEWS_FOR_MAX_QUALITY = 25;

    public ReputationService(
            ReviewRepository reviewRepo,
            BusinessRepository businessRepo,
            BadgeService badgeService
    ) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
        this.badgeService = badgeService;
    }

    /**
     * FIXED: Calculate Wilson Score-based Reputul Rating (0-5 stars)
     * Uses weighted average of actual star ratings with confidence adjustment
     */
    public double calculateReputulRating(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) {
            return 0.0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        double totalWeightedReviews = 0.0;
        double totalWeightedRating = 0.0;

        // Apply recency weighting to each review
        for (Review review : reviews) {
            long ageDays = ChronoUnit.DAYS.between(review.getCreatedAt(), now);
            double recencyWeight = Math.exp(-RECENCY_LAMBDA * ageDays);

            totalWeightedReviews += recencyWeight;
            totalWeightedRating += (review.getRating() * recencyWeight);
        }

        if (totalWeightedReviews == 0) {
            return 0.0;
        }

        // Calculate weighted average
        double weightedAverage = totalWeightedRating / totalWeightedReviews;

        // Apply confidence adjustment for small samples
        // This prevents businesses with few perfect reviews from outranking established ones
        double n = totalWeightedReviews;
        double z = WILSON_CONFIDENCE;

        // For star ratings (1-5), we calculate the uncertainty and adjust downward
        // The variance of a rating between 1-5 is approximated by the spread from the mean
        double maxRating = 5.0;
        double variance = weightedAverage * (maxRating - weightedAverage) / maxRating;
        double standardError = Math.sqrt(variance / n);
        double marginOfError = z * standardError;

        // Adjust rating downward by margin of error, accounting for sample size
        double confidenceAdjustment = marginOfError / (1 + (z * z) / n);
        double adjustedRating = weightedAverage - confidenceAdjustment;

        // Ensure within bounds
        double finalRating = Math.max(0.0, Math.min(5.0, adjustedRating));

        log.debug("Business {}: {} reviews, weighted avg: {:.2f}, adjusted: {:.2f}",
                businessId, reviews.size(), weightedAverage, finalRating);

        return finalRating;
    }

    /**
     * Calculate composite Reputation Score (0-100)
     * 60% Quality + 25% Velocity + 15% Responsiveness
     */
    public double calculateCompositeReputationScore(Long businessId) {
        double qualityScore = calculateQualityScore(businessId);
        double velocityScore = calculateVelocityScore(businessId);
        double responsivenessScore = calculateResponsivenessScore(businessId);

        double compositeScore = (QUALITY_WEIGHT * qualityScore) +
                (VELOCITY_WEIGHT * velocityScore) +
                (RESPONSIVENESS_WEIGHT * responsivenessScore);

        return Math.max(0.0, Math.min(100.0, compositeScore));
    }

    /**
     * BACKWARD COMPATIBILITY: Keep the old method signature
     */
    public double getReputationScore(Long businessId) {
        return calculateCompositeReputationScore(businessId);
    }

    /**
     * Update all reputation metrics for a business
     */
    @Transactional
    public void updateBusinessReputationAndBadge(Long businessId) {
        Business business = businessRepo.findById(businessId)
                .orElseThrow(() -> new RuntimeException("Business not found"));

        // Calculate all new metrics
        double reputulRating = calculateReputulRating(businessId);
        double qualityScore = calculateQualityScore(businessId);
        double velocityScore = calculateVelocityScore(businessId);
        double responsivenessScore = calculateResponsivenessScore(businessId);
        double compositeScore = calculateCompositeReputationScore(businessId);

        // Update all fields in business entity
        business.setReputulRating(reputulRating);
        business.setReputationScoreQuality(qualityScore);
        business.setReputationScoreVelocity(velocityScore);
        business.setReputationScoreResponsiveness(responsivenessScore);
        business.setReputationScoreComposite(compositeScore);
        business.setLastReputationUpdate(OffsetDateTime.now());

        // Update legacy reputation score for backward compatibility
        business.setReputationScore(compositeScore);

        // Update badge based on Wilson Score
        String newBadge = badgeService.determineEnhancedBadgeWithActivity(
                businessId,
                reputulRating,
                Math.toIntExact(reviewRepo.countByBusinessId(businessId))
        );
        business.setBadge(newBadge);

        businessRepo.save(business);

        log.info("Updated reputation metrics for business {}: Reputul Rating={:.2f}, Composite Score={:.0f}, Badge={}",
                businessId, reputulRating, compositeScore, newBadge);
    }

    /**
     * Core Wilson Score Lower Bound calculation
     */
    private double calculateWilsonLowerBound(double positiveRate, double sampleSize) {
        if (sampleSize == 0) {
            return 0.0;
        }

        double p = positiveRate;
        double n = sampleSize;
        double z = WILSON_CONFIDENCE;

        double denominator = 1 + (z * z) / n;
        double pAdjusted = p + (z * z) / (2 * n);
        double errorTerm = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * n)) / n);

        double lowerBound = (pAdjusted - errorTerm) / denominator;

        return Math.max(0.0, lowerBound);
    }

    /**
     * Quality Score (0-100): Based on Wilson-derived positive share with volume guardrails
     */
    private double calculateQualityScore(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) {
            return 0.0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        double totalWeightedReviews = 0.0;
        double totalWeightedPositive = 0.0;

        for (Review review : reviews) {
            long ageDays = ChronoUnit.DAYS.between(review.getCreatedAt(), now);
            double weight = Math.exp(-RECENCY_LAMBDA * ageDays);

            totalWeightedReviews += weight;
            if (review.getRating() >= 4) {
                totalWeightedPositive += weight;
            }
        }

        if (totalWeightedReviews == 0) {
            return 0.0;
        }

        double p = totalWeightedPositive / totalWeightedReviews;
        double n = totalWeightedReviews;

        // Wilson score lower bound
        double wilsonLowerBound = calculateWilsonLowerBound(p, n);

        // Convert to 0-100 scale
        double qualityScore = wilsonLowerBound * 100.0;

        // Apply volume guardrail - cap at 85 if less than minimum volume
        if (reviews.size() < MIN_REVIEWS_FOR_MAX_QUALITY) {
            qualityScore = Math.min(qualityScore, 85.0);
        }

        return Math.max(0.0, Math.min(100.0, qualityScore));
    }

    /**
     * Velocity Score (0-100): Review gathering pace and recency mix
     */
    private double calculateVelocityScore(Long businessId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last90Days = now.minusDays(90);
        OffsetDateTime last12Months = now.minusDays(365);

        // Count reviews in different time periods
        long reviewsLast90d = reviewRepo.findByBusinessId(businessId).stream()
                .filter(r -> r.getCreatedAt().isAfter(last90Days))
                .count();

        long reviewsLast12m = reviewRepo.findByBusinessId(businessId).stream()
                .filter(r -> r.getCreatedAt().isAfter(last12Months))
                .count();

        if (reviewsLast12m == 0) {
            return 0.0;
        }

        // 1. Velocity component: reviews per 30 days vs baseline
        double reviewsPer30d = (reviewsLast90d / 90.0) * 30.0;
        double industryBaseline = 1.5; // Adjusted baseline for local services
        double velocityComponent = Math.min(1.0, reviewsPer30d / industryBaseline);

        // 2. Recency mix: share of last 90 days vs last 12 months
        double recencyMix = (double) reviewsLast90d / reviewsLast12m;

        // Apply sigmoid smoothing to avoid punishing seasonality
        double smoothedRecencyMix = 1.0 / (1.0 + Math.exp(-5.0 * (recencyMix - 0.3)));

        // Combine: 70% velocity + 30% recency mix
        double velocityScore = 100.0 * (0.7 * velocityComponent + 0.3 * smoothedRecencyMix);

        return Math.max(0.0, Math.min(100.0, velocityScore));
    }

    /**
     * Responsiveness Score (0-100): Response rate and speed
     * Note: Currently returns default score since response tracking is Phase 2
     */
    private double calculateResponsivenessScore(Long businessId) {
        // TODO: Implement when response tracking is added to Review entity
        // For now, return a neutral score
        return 50.0;
    }

    /**
     * Get detailed reputation breakdown for dashboard
     */
    public ReputationBreakdown getReputationBreakdown(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        double reputulRating = calculateReputulRating(businessId);
        double qualityScore = calculateQualityScore(businessId);
        double velocityScore = calculateVelocityScore(businessId);
        double responsivenessScore = calculateResponsivenessScore(businessId);
        double compositeScore = calculateCompositeReputationScore(businessId);

        int totalReviews = reviews.size();
        int positiveReviews = (int) reviews.stream().filter(r -> r.getRating() >= 4).count();

        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(90);
        int reviewsLast90d = (int) reviews.stream()
                .filter(r -> r.getCreatedAt().isAfter(cutoff))
                .count();

        return new ReputationBreakdown(
                businessId,
                reputulRating,
                qualityScore,
                velocityScore,
                responsivenessScore,
                compositeScore,
                totalReviews,
                positiveReviews,
                reviewsLast90d
        );
    }

    /**
     * Data class for reputation breakdown
     */
    public static class ReputationBreakdown {
        public final Long businessId;
        public final double reputulRating;
        public final double qualityScore;
        public final double velocityScore;
        public final double responsivenessScore;
        public final double compositeScore;
        public final int totalReviews;
        public final int positiveReviews;
        public final int reviewsLast90d;

        public ReputationBreakdown(Long businessId, double reputulRating, double qualityScore,
                                   double velocityScore, double responsivenessScore, double compositeScore,
                                   int totalReviews, int positiveReviews, int reviewsLast90d) {
            this.businessId = businessId;
            this.reputulRating = reputulRating;
            this.qualityScore = qualityScore;
            this.velocityScore = velocityScore;
            this.responsivenessScore = responsivenessScore;
            this.compositeScore = compositeScore;
            this.totalReviews = totalReviews;
            this.positiveReviews = positiveReviews;
            this.reviewsLast90d = reviewsLast90d;
        }

        public String getColorBand() {
            if (compositeScore >= 76) return "green";
            if (compositeScore >= 46) return "yellow";
            return "red";
        }
    }
}