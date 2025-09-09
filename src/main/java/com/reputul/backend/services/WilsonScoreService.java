package com.reputul.backend.services;

import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Wilson Score Calculator - The core of our enhanced reputation system
 * This prevents small-sample bias that makes 3 perfect reviews beat 200 reviews at 4.7★
 */
@Service
@Slf4j
public class WilsonScoreService {

    private final ReviewRepository reviewRepo;

    // Wilson confidence interval at 95% confidence
    private static final double WILSON_CONFIDENCE = 1.96;
    // Recency weight: ~180 day half-life (ln(2)/180)
    private static final double RECENCY_LAMBDA = 0.00385;

    public WilsonScoreService(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    /**
     * Calculate Wilson Score-based Reputul Rating (0-5 stars)
     * This is the NEW public rating that prevents small-sample bias
     */
    public double calculateWilsonScoreRating(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) {
            return 0.0;
        }

        log.info("Calculating Wilson Score for business {} with {} reviews", businessId, reviews.size());

        OffsetDateTime now = OffsetDateTime.now();
        double totalWeightedReviews = 0.0;
        double totalWeightedPositive = 0.0;

        // Apply recency weighting to each review
        for (Review review : reviews) {
            // Calculate how old this review is
            long ageDays = ChronoUnit.DAYS.between(review.getCreatedAt(), now);

            // Apply exponential decay: recent reviews count more
            double recencyWeight = Math.exp(-RECENCY_LAMBDA * ageDays);

            totalWeightedReviews += recencyWeight;

            // Count 4-5 star reviews as "positive"
            if (review.getRating() >= 4) {
                totalWeightedPositive += recencyWeight;
            }

            log.debug("Review age: {} days, weight: {:.3f}, rating: {}",
                    ageDays, recencyWeight, review.getRating());
        }

        if (totalWeightedReviews == 0) {
            return 0.0;
        }

        // Calculate positive rate (what fraction are 4-5 stars)
        double positiveRate = totalWeightedPositive / totalWeightedReviews;
        double effectiveSampleSize = totalWeightedReviews;

        log.info("Positive rate: {:.3f}, Effective sample size: {:.1f}",
                positiveRate, effectiveSampleSize);

        // Wilson Score Lower Bound calculation
        // This prevents small samples from having inflated confidence
        double wilsonLowerBound = calculateWilsonLowerBound(positiveRate, effectiveSampleSize);

        // Convert from 0-1 (positive rate) back to 0-5 star scale
        double reputulRating = wilsonLowerBound * 5.0;

        // Ensure it's within bounds
        reputulRating = Math.max(0.0, Math.min(5.0, reputulRating));

        log.info("Wilson lower bound: {:.3f}, Final Reputul Rating: {:.2f}",
                wilsonLowerBound, reputulRating);

        return reputulRating;
    }

    /**
     * Core Wilson Score Lower Bound calculation
     * This is the mathematical foundation that prevents small-sample bias
     */
    private double calculateWilsonLowerBound(double positiveRate, double sampleSize) {
        if (sampleSize == 0) {
            return 0.0;
        }

        double p = positiveRate; // success rate
        double n = sampleSize;   // sample size
        double z = WILSON_CONFIDENCE; // 1.96 for 95% confidence

        // Wilson Score Interval formula (lower bound)
        double denominator = 1 + (z * z) / n;
        double pAdjusted = p + (z * z) / (2 * n);
        double errorTerm = z * Math.sqrt((p * (1 - p) + (z * z) / (4 * n)) / n);

        double lowerBound = (pAdjusted - errorTerm) / denominator;

        return Math.max(0.0, lowerBound);
    }

    /**
     * COMPARISON METHOD: Get both old and new ratings for analysis
     * Use this to see the difference Wilson Score makes with your real data
     */
    public ComparisonResult compareRatings(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) {
            return new ComparisonResult(0.0, 0.0, 0, "No reviews");
        }

        // OLD method: Simple average
        double oldRating = reviews.stream()
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);

        // NEW method: Wilson Score
        double newRating = calculateWilsonScoreRating(businessId);

        // Analysis
        String analysis = analyzeRatingDifference(oldRating, newRating, reviews.size());

        return new ComparisonResult(oldRating, newRating, reviews.size(), analysis);
    }

    private String analyzeRatingDifference(double oldRating, double newRating, int reviewCount) {
        double difference = newRating - oldRating;

        if (Math.abs(difference) < 0.1) {
            return "Similar ratings - large sample with consistent quality";
        } else if (difference < -0.5) {
            return "Wilson Score is much lower - likely small sample with inflated simple average";
        } else if (difference > 0.2) {
            return "Wilson Score is higher - recent reviews are very positive";
        } else {
            return String.format("Difference: %.2f - Wilson Score accounts for sample size and recency", difference);
        }
    }

    /**
     * Get detailed breakdown for debugging/analysis
     */
    public DetailedResult getDetailedAnalysis(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) {
            return new DetailedResult(businessId, 0, 0, 0, 0.0, 0.0, 0.0, 0.0);
        }

        OffsetDateTime now = OffsetDateTime.now();
        double totalWeightedReviews = 0.0;
        double totalWeightedPositive = 0.0;
        int positiveCount = 0;

        for (Review review : reviews) {
            long ageDays = ChronoUnit.DAYS.between(review.getCreatedAt(), now);
            double recencyWeight = Math.exp(-RECENCY_LAMBDA * ageDays);

            totalWeightedReviews += recencyWeight;

            if (review.getRating() >= 4) {
                totalWeightedPositive += recencyWeight;
                positiveCount++;
            }
        }

        double oldAverage = reviews.stream().mapToInt(Review::getRating).average().orElse(0.0);
        double positiveRate = totalWeightedReviews > 0 ? totalWeightedPositive / totalWeightedReviews : 0.0;
        double wilsonLowerBound = calculateWilsonLowerBound(positiveRate, totalWeightedReviews);
        double newRating = wilsonLowerBound * 5.0;

        return new DetailedResult(
                businessId,
                reviews.size(),
                positiveCount,
                totalWeightedReviews,
                oldAverage,
                positiveRate,
                wilsonLowerBound,
                newRating
        );
    }

    /**
     * Helper class for comparison results
     */
    public static class ComparisonResult {
        public final double oldRating;
        public final double newRating;
        public final int reviewCount;
        public final String analysis;

        public ComparisonResult(double oldRating, double newRating, int reviewCount, String analysis) {
            this.oldRating = oldRating;
            this.newRating = newRating;
            this.reviewCount = reviewCount;
            this.analysis = analysis;
        }

        @Override
        public String toString() {
            return String.format("Reviews: %d | Old: %.2f | New: %.2f | %s",
                    reviewCount, oldRating, newRating, analysis);
        }
    }

    /**
     * Detailed breakdown for analysis
     */
    public static class DetailedResult {
        public final Long businessId;
        public final int totalReviews;
        public final int positiveReviews;
        public final double effectiveSampleSize;
        public final double oldAverage;
        public final double positiveRate;
        public final double wilsonLowerBound;
        public final double newRating;

        public DetailedResult(Long businessId, int totalReviews, int positiveReviews,
                              double effectiveSampleSize, double oldAverage, double positiveRate,
                              double wilsonLowerBound, double newRating) {
            this.businessId = businessId;
            this.totalReviews = totalReviews;
            this.positiveReviews = positiveReviews;
            this.effectiveSampleSize = effectiveSampleSize;
            this.oldAverage = oldAverage;
            this.positiveRate = positiveRate;
            this.wilsonLowerBound = wilsonLowerBound;
            this.newRating = newRating;
        }

        @Override
        public String toString() {
            return String.format(
                    "Business %d: %d reviews (%d positive) | Old: %.2f | Wilson: %.2f | Effective size: %.1f",
                    businessId, totalReviews, positiveReviews, oldAverage, newRating, effectiveSampleSize
            );
        }
    }
}