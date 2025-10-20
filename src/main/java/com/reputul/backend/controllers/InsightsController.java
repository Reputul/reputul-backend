package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReputationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for providing comprehensive insights data for businesses
 * Supports the Insights page with analytics, goals, and trends
 */
@RestController
@RequestMapping("/api/v1/insights")
@Slf4j
public class InsightsController {

    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final ReputationService reputationService;

    public InsightsController(
            BusinessRepository businessRepository,
            ReviewRepository reviewRepository,
            UserRepository userRepository,
            ReputationService reputationService
    ) {
        this.businessRepository = businessRepository;
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
        this.reputationService = reputationService;
    }

    /**
     * Get comprehensive insights data for a specific business
     * GET /api/v1/insights/business/{businessId}?period=30d
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<Map<String, Object>> getBusinessInsights(
            @PathVariable Long businessId,
            @RequestParam(defaultValue = "30d") String period,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Verify user owns this business
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepository.findById(businessId)
                    .orElse(null);

            if (business == null) {
                log.warn("Business {} not found", businessId);
                return ResponseEntity.notFound().build();
            }

            // Verify business ownership using user relationship
            if (business.getUser() == null || !business.getUser().getId().equals(user.getId())) {
                log.warn("User {} attempted to access business {} they don't own", email, businessId);
                return ResponseEntity.notFound().build();
            }

            // Parse period parameter
            OffsetDateTime periodStart = parsePeriod(period);

            // Get all reviews for this business
            List<Review> allReviews = reviewRepository.findByBusinessIdOrderByCreatedAtDesc(businessId);

            // Get reviews within the specified period
            List<Review> periodReviews = allReviews.stream()
                    .filter(review -> review.getCreatedAt().isAfter(periodStart))
                    .collect(Collectors.toList());

            // Get reputation metrics
            ReputationService.ReputationBreakdown reputation = reputationService.getReputationBreakdown(businessId);

            // Build comprehensive insights response
            Map<String, Object> insights = new HashMap<>();

            // Overall rating and distribution
            insights.put("overallRating", calculateOverallRating(allReviews));
            insights.put("totalReviews", allReviews.size());
            insights.put("reviewDistribution", calculateReviewDistribution(allReviews));

            // Reputation metrics
            Map<String, Object> reputationMetrics = new HashMap<>();
            reputationMetrics.put("score", Math.round(reputation.compositeScore));
            reputationMetrics.put("badge", business.getBadge() != null ? business.getBadge() : "Unranked");
            reputationMetrics.put("wilsonScore", reputation.reputulRating);
            insights.put("reputationMetrics", reputationMetrics);

            // Rating goals
            insights.put("ratingGoals", calculateRatingGoals(allReviews));

            // Platform performance
            insights.put("platformPerformance", calculatePlatformPerformance(allReviews));

            // Sentiment breakdown
            insights.put("sentiment", calculateSentiment(allReviews));

            // Time series data
            insights.put("timeSeries", calculateTimeSeries(allReviews, 12)); // Last 12 months

            // Statistics
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("averagePerMonth", calculateAveragePerMonth(allReviews));
            statistics.put("totalSinceJoining", allReviews.size());
            statistics.put("memberSince", business.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            insights.put("statistics", statistics);

            return ResponseEntity.ok(insights);

        } catch (Exception e) {
            log.error("Error getting insights for business {}: {}", businessId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Parse period parameter (e.g., "30d", "90d", "365d")
     */
    private OffsetDateTime parsePeriod(String period) {
        try {
            if (period.endsWith("d")) {
                int days = Integer.parseInt(period.substring(0, period.length() - 1));
                return OffsetDateTime.now().minusDays(days);
            }
            // Default to 30 days
            return OffsetDateTime.now().minusDays(30);
        } catch (Exception e) {
            log.warn("Invalid period parameter: {}, defaulting to 30 days", period);
            return OffsetDateTime.now().minusDays(30);
        }
    }

    /**
     * Calculate overall rating from all reviews
     */
    private double calculateOverallRating(List<Review> reviews) {
        if (reviews.isEmpty()) return 0.0;

        double sum = reviews.stream()
                .mapToInt(Review::getRating)
                .sum();

        return Math.round((sum / reviews.size()) * 10.0) / 10.0;
    }

    /**
     * Calculate review distribution by platform
     */
    private List<Map<String, Object>> calculateReviewDistribution(List<Review> reviews) {
        Map<String, List<Review>> byPlatform = reviews.stream()
                .collect(Collectors.groupingBy(review ->
                        review.getSource() != null ? review.getSource() : "DIRECT"));

        return byPlatform.entrySet().stream()
                .map(entry -> {
                    String platform = entry.getKey();
                    List<Review> platformReviews = entry.getValue();

                    Map<String, Object> platformData = new HashMap<>();
                    platformData.put("platform", formatPlatformName(platform));
                    platformData.put("count", platformReviews.size());
                    platformData.put("avgRating", calculateOverallRating(platformReviews));

                    return platformData;
                })
                .sorted((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")))
                .collect(Collectors.toList());
    }

    /**
     * Format platform name for display
     */
    private String formatPlatformName(String source) {
        switch (source.toUpperCase()) {
            case "GOOGLE": return "Google";
            case "FACEBOOK": return "Facebook";
            case "YELP": return "Yelp";
            case "DIRECT": return "Direct";
            case "WEBSITE": return "Website";
            default: return source.charAt(0) + source.substring(1).toLowerCase();
        }
    }

    /**
     * Calculate rating goals (how many 5-star reviews needed to reach targets)
     */
    private List<Map<String, Object>> calculateRatingGoals(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return Arrays.asList(
                    createRatingGoal(4.8, 0, 0, 0, 0),
                    createRatingGoal(4.9, 0, 0, 0, 0),
                    createRatingGoal(5.0, 0, 0, 0, 0)
            );
        }

        double currentRating = calculateOverallRating(reviews);
        int totalReviews = reviews.size();
        double currentSum = currentRating * totalReviews;

        // Calculate monthly review rate (reviews per month over last 12 months)
        double monthlyReviewRate = calculateAveragePerMonth(reviews);

        return Arrays.asList(
                createRatingGoal(4.8, currentRating, currentSum, totalReviews, monthlyReviewRate),
                createRatingGoal(4.9, currentRating, currentSum, totalReviews, monthlyReviewRate),
                createRatingGoal(5.0, currentRating, currentSum, totalReviews, monthlyReviewRate)
        );
    }

    /**
     * Create a rating goal object
     */
    private Map<String, Object> createGoal(double target, int reviewsNeeded) {
        Map<String, Object> goal = new HashMap<>();
        goal.put("target", target);
        goal.put("reviewsNeeded", reviewsNeeded);
        return goal;
    }

    /**
     * Calculate how many 5-star reviews are needed to reach a target rating
     */
    private Map<String, Object> createRatingGoal(double target, double currentRating, double currentSum, int totalReviews, double monthlyReviewRate) {
        Map<String, Object> goal = new HashMap<>();
        goal.put("target", target);

        if (currentRating >= target) {
            goal.put("reviewsNeeded", 0);
            goal.put("progress", 100);
            goal.put("isAchieved", true);
            goal.put("projectedMonths", 0);
        } else {
            // Calculate reviews needed using proper formula
            double reviewsNeeded = (target * totalReviews - currentSum) / (5.0 - target);

            int finalReviewsNeeded;
            // Handle edge cases
            if (target >= 5.0) {
                // For 5.0, it's theoretically impossible without perfect reviews
                // Show a large but capped number
                finalReviewsNeeded = Math.min(9999, (int) Math.ceil(reviewsNeeded));
            } else if (reviewsNeeded <= 0) {
                finalReviewsNeeded = 0;
            } else {
                finalReviewsNeeded = (int) Math.ceil(reviewsNeeded);
            }

            goal.put("reviewsNeeded", finalReviewsNeeded);

            // Calculate projected time to reach target
            if (finalReviewsNeeded > 0 && monthlyReviewRate > 0) {
                int projectedMonths = (int) Math.ceil(finalReviewsNeeded / monthlyReviewRate);
                goal.put("projectedMonths", projectedMonths);
            } else if (finalReviewsNeeded == 0) {
                goal.put("projectedMonths", 0);
            } else {
                // No review rate data or impossible target
                goal.put("projectedMonths", null);
            }

            // Calculate progress as percentage toward target
            double progress = (currentRating / target) * 100;
            goal.put("progress", Math.min(100, Math.max(0, progress)));
            goal.put("isAchieved", false);
        }

        return goal;
    }

    /**
     * Calculate platform performance for horizontal bar chart
     */
    private List<Map<String, Object>> calculatePlatformPerformance(List<Review> reviews) {
        Map<String, List<Review>> byPlatform = reviews.stream()
                .collect(Collectors.groupingBy(review ->
                        review.getSource() != null ? review.getSource() : "DIRECT"));

        return byPlatform.entrySet().stream()
                .map(entry -> {
                    String platform = entry.getKey();
                    List<Review> platformReviews = entry.getValue();

                    Map<String, Object> performance = new HashMap<>();
                    performance.put("name", formatPlatformName(platform));
                    performance.put("rating", calculateOverallRating(platformReviews));
                    performance.put("count", platformReviews.size());
                    performance.put("color", getPlatformColor(platform));

                    return performance;
                })
                .sorted((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")))
                .collect(Collectors.toList());
    }

    /**
     * Get brand color for each platform
     */
    private String getPlatformColor(String platform) {
        switch (platform.toUpperCase()) {
            case "GOOGLE": return "#4285F4";
            case "FACEBOOK": return "#1877F2";
            case "YELP": return "#FF1A1A";
            case "DIRECT": return "#10B981";
            case "WEBSITE": return "#8B5CF6";
            default: return "#6B7280";
        }
    }

    /**
     * Calculate sentiment breakdown (positive vs negative)
     */
    private Map<String, Object> calculateSentiment(List<Review> reviews) {
        if (reviews.isEmpty()) {
            return Map.of(
                    "positive", Map.of("count", 0, "percentage", 0),
                    "negative", Map.of("count", 0, "percentage", 0)
            );
        }

        long positiveCount = reviews.stream()
                .filter(review -> review.getRating() >= 4)
                .count();

        long negativeCount = reviews.size() - positiveCount;

        int positivePercent = (int) Math.round((double) positiveCount / reviews.size() * 100);
        int negativePercent = 100 - positivePercent;

        return Map.of(
                "positive", Map.of("count", positiveCount, "percentage", positivePercent),
                "negative", Map.of("count", negativeCount, "percentage", negativePercent)
        );
    }

    /**
     * Calculate time series data for the last N months
     */
    private List<Map<String, Object>> calculateTimeSeries(List<Review> reviews, int months) {
        List<Map<String, Object>> timeSeries = new ArrayList<>();

        // Group reviews by month
        Map<String, List<Review>> reviewsByMonth = reviews.stream()
                .collect(Collectors.groupingBy(review -> {
                    LocalDate date = review.getCreatedAt().toLocalDate();
                    return date.getYear() + "-" + String.format("%02d", date.getMonthValue());
                }));

        // Generate last N months
        LocalDate currentDate = LocalDate.now();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate monthDate = currentDate.minusMonths(i);
            String monthKey = monthDate.getYear() + "-" + String.format("%02d", monthDate.getMonthValue());
            String monthDisplay = monthDate.format(DateTimeFormatter.ofPattern("MMM yyyy"));

            List<Review> monthReviews = reviewsByMonth.getOrDefault(monthKey, new ArrayList<>());

            Map<String, Object> monthData = new HashMap<>();
            monthData.put("date", monthKey);
            monthData.put("month", monthDisplay);
            monthData.put("count", monthReviews.size());
            monthData.put("avgRating", monthReviews.isEmpty() ? 0.0 : calculateOverallRating(monthReviews));

            timeSeries.add(monthData);
        }

        return timeSeries;
    }

    /**
     * Calculate average reviews per month
     */
    private double calculateAveragePerMonth(List<Review> reviews) {
        if (reviews.isEmpty()) return 0.0;

        // Find the oldest review date
        OffsetDateTime oldestDate = reviews.stream()
                .map(Review::getCreatedAt)
                .min(OffsetDateTime::compareTo)
                .orElse(OffsetDateTime.now());

        // Calculate months since oldest review
        long monthsSinceStart = java.time.temporal.ChronoUnit.MONTHS.between(
                oldestDate.toLocalDate().withDayOfMonth(1),
                OffsetDateTime.now().toLocalDate().withDayOfMonth(1)
        ) + 1; // Include current month

        if (monthsSinceStart == 0) monthsSinceStart = 1;

        return Math.round((double) reviews.size() / monthsSinceStart * 10.0) / 10.0;
    }
}