package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Enhanced Badge Service using Wilson Score for fairer badge determination
 */
@Service
public class BadgeService {

    private final ReviewRepository reviewRepo;

    public BadgeService(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    /**
     * NEW: Enhanced badge determination using Wilson Score ratings
     * Based on Reputul Rating + volume + recent activity requirements
     */
    public String determineEnhancedBadge(double reputulRating, int totalReviews) {
        // Get recent activity count (last 90 days)
        // Note: This is simplified - in production you'd pass businessId and calculate properly
        int recentReviews = Math.max(1, totalReviews / 10); // Simplified estimate

        // Neighborhood Favorite: Excellent Wilson score + substantial volume + recent activity
        if (reputulRating >= 4.5 && totalReviews >= 20 && recentReviews >= 2) {
            return "Neighborhood Favorite";
        }

        // Top Rated: High Wilson score + good volume + some recent activity
        if (reputulRating >= 4.0 && totalReviews >= 10 && recentReviews >= 1) {
            return "Top Rated";
        }

        // Rising Star: Decent Wilson score + recent momentum
        if (reputulRating >= 3.5 && totalReviews >= 5 && recentReviews >= 1) {
            return "Rising Star";
        }

        // No badge criteria met
        if (totalReviews == 0) {
            return "No Reviews Yet";
        }

        return "Unranked";
    }

    /**
     * NEW: Enhanced badge with detailed activity analysis
     */
    public String determineEnhancedBadgeWithActivity(Long businessId, double reputulRating, int totalReviews) {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(90);

        // Count recent reviews properly
        int recentReviews = (int) reviewRepo.findByBusinessId(businessId).stream()
                .filter(r -> r.getCreatedAt().isAfter(cutoff))
                .count();

        // Neighborhood Favorite: Excellent scores + substantial volume + recent activity
        if (reputulRating >= 4.5 && totalReviews >= 20 && recentReviews >= 2) {
            return "Neighborhood Favorite";
        }

        // Top Rated: High scores + good volume + some recent activity
        if (reputulRating >= 4.0 && totalReviews >= 10 && recentReviews >= 1) {
            return "Top Rated";
        }

        // Rising Star: Decent scores + recent momentum
        if (reputulRating >= 3.5 && totalReviews >= 5 && recentReviews >= 1) {
            return "Rising Star";
        }

        // No badge criteria met
        if (totalReviews == 0) {
            return "No Reviews Yet";
        }

        return "Unranked";
    }

    /**
     * BACKWARD COMPATIBILITY: Updated to use Wilson Score
     * Now uses Reputul Rating instead of simple average
     */
    public String updateBusinessBadge(Business business) {
        // Use the new Reputul Rating if available, fall back to old calculation
        double rating = business.getReputulRating() != null ?
                business.getReputulRating() :
                (business.getReputationScore() != null ? business.getReputationScore() / 20.0 : 0.0);

        long totalReviews = reviewRepo.countByBusinessId(business.getId());
        String newBadge = determineEnhancedBadge(rating, (int) totalReviews);

        business.setBadge(newBadge);
        return newBadge;
    }

    /**
     * Get badge explanation for UI tooltips
     */
    public String getBadgeExplanation(String badge) {
        return switch (badge) {
            case "Neighborhood Favorite" ->
                    "Exceptional service with 4.5+ Wilson Score rating, 20+ reviews, and recent activity";
            case "Top Rated" ->
                    "High-quality service with 4.0+ Wilson Score rating, 10+ reviews, and recent activity";
            case "Rising Star" ->
                    "Growing reputation with 3.5+ Wilson Score rating, 5+ reviews, and recent momentum";
            case "No Reviews Yet" ->
                    "This business hasn't received any reviews yet";
            default ->
                    "This business doesn't meet the criteria for a reputation badge yet";
        };
    }

    /**
     * Get badge color for UI styling
     */
    public String getBadgeColor(String badge) {
        return switch (badge) {
            case "Neighborhood Favorite" -> "bg-gradient-to-r from-purple-500 to-purple-600 text-white";
            case "Top Rated" -> "bg-gradient-to-r from-green-500 to-emerald-600 text-white";
            case "Rising Star" -> "bg-gradient-to-r from-yellow-500 to-orange-500 text-white";
            case "No Reviews Yet" -> "bg-gradient-to-r from-gray-300 to-gray-400 text-gray-800";
            default -> "bg-gradient-to-r from-gray-400 to-gray-500 text-white";
        };
    }

    /**
     * Determine next badge tier and requirements
     */
    public String getNextBadgeRequirements(double reputulRating, int totalReviews, int recentReviews) {
        String currentBadge = determineEnhancedBadge(reputulRating, totalReviews);

        return switch (currentBadge) {
            case "No Reviews Yet" ->
                    "Get 5 reviews with 3.5+ Wilson Score rating to earn Rising Star";
            case "Unranked" -> {
                if (totalReviews < 5) yield "Need " + (5 - totalReviews) + " more reviews for Rising Star";
                if (reputulRating < 3.5) yield "Improve rating quality to 3.5+ for Rising Star";
                yield "Get more recent reviews for Rising Star";
            }
            case "Rising Star" -> {
                if (totalReviews < 10) yield "Need " + (10 - totalReviews) + " more reviews for Top Rated";
                if (reputulRating < 4.0) yield "Improve Wilson Score rating to 4.0+ for Top Rated";
                yield "Maintain recent activity for Top Rated";
            }
            case "Top Rated" -> {
                if (totalReviews < 20) yield "Need " + (20 - totalReviews) + " more reviews for Neighborhood Favorite";
                if (reputulRating < 4.5) yield "Improve Wilson Score rating to 4.5+ for Neighborhood Favorite";
                yield "Get 2+ recent reviews for Neighborhood Favorite";
            }
            default -> "You've achieved the highest badge level!";
        };
    }

    /**
     * BACKWARD COMPATIBILITY: Keep old methods but mark as deprecated
     */
    @Deprecated
    public String determineBadge(double avgRating, long totalReviews) {
        // Convert simple average to approximate Wilson equivalent for compatibility
        double adjustedRating = avgRating * 0.85; // Rough adjustment for Wilson Score behavior
        return determineEnhancedBadge(adjustedRating, (int) totalReviews);
    }

    @Deprecated
    public String determineBadge(double avgRating, int totalReviews) {
        return determineBadge(avgRating, (long) totalReviews);
    }
}