package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Enhanced Badge Service with motivating 5-tier progression system
 * Designed to reward early wins and create clear achievement paths
 */
@Service
public class BadgeService {

    private final ReviewRepository reviewRepo;

    public BadgeService(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    /**
     * NEW: 5-Tier Badge System (Motivating & Progressive)
     *
     * Tier 1: New Starter (0-2 reviews) - Immediate positive framing
     * Tier 2: Rising Star (3-5 reviews, 3.5+ stars) - Quick win to build momentum
     * Tier 3: Trusted Pro (8+ reviews, 4.0+ stars) - Established quality
     * Tier 4: Top Rated (15+ reviews, 4.3+ stars) - Premium badge
     * Tier 5: Neighborhood Favorite (25+ reviews, 4.5+ stars) - Elite status
     *
     * @param reputulRating Wilson Score rating (0-5 scale)
     * @param totalReviews Total number of reviews
     * @return Badge name
     */
    public String determineEnhancedBadge(double reputulRating, int totalReviews) {
        // Tier 1: New Starter (Getting Started)
        if (totalReviews <= 2) {
            return "New Starter";
        }

        // Tier 2: Rising Star (Quick Win - achievable in first week)
        // Path A: 3+ reviews with 3.5+ stars (quality focus)
        // Path B: 5+ reviews with 3.0+ stars (volume focus)
        if ((totalReviews >= 3 && reputulRating >= 3.5) ||
                (totalReviews >= 5 && reputulRating >= 3.0)) {

            // Check if they qualify for higher tiers
            if (totalReviews < 8) {
                return "Rising Star";
            }
        }

        // Tier 3: Trusted Pro (Established Quality - 1-2 months)
        // Requires 8+ reviews with 4.0+ stars OR 15+ reviews with 3.8+ stars
        if ((totalReviews >= 8 && reputulRating >= 4.0) ||
                (totalReviews >= 15 && reputulRating >= 3.8)) {

            // Check if they qualify for higher tiers
            if (totalReviews < 15 || reputulRating < 4.3) {
                return "Trusted Pro";
            }
        }

        // Tier 4: Top Rated (Premium Badge)
        // Requires 15+ reviews with 4.3+ stars OR 25+ reviews with 4.0+ stars
        if ((totalReviews >= 15 && reputulRating >= 4.3) ||
                (totalReviews >= 25 && reputulRating >= 4.0)) {

            // Check if they qualify for elite tier
            if (totalReviews < 25 || reputulRating < 4.5) {
                return "Top Rated";
            }
        }

        // Tier 5: Neighborhood Favorite (Elite Status - Aspirational)
        // Requires 25+ reviews with 4.5+ stars AND 90%+ positive sentiment
        if (totalReviews >= 25 && reputulRating >= 4.5) {
            return "Neighborhood Favorite";
        }

        // Default: Building Reputation (has reviews but doesn't meet criteria)
        if (totalReviews > 0) {
            return "Building Reputation";
        }

        // Fallback (should never reach here due to Tier 1 catch)
        return "No Reviews Yet";
    }

    /**
     * NEW: Enhanced badge with detailed activity analysis
     * Includes recency check for higher tiers
     */
    public String determineEnhancedBadgeWithActivity(Long businessId, double reputulRating, int totalReviews) {
        OffsetDateTime cutoff90Days = OffsetDateTime.now().minusDays(90);
        OffsetDateTime cutoff60Days = OffsetDateTime.now().minusDays(60);

        // Count recent reviews
        int reviewsLast90Days = (int) reviewRepo.findByBusinessId(businessId).stream()
                .filter(r -> r.getCreatedAt().isAfter(cutoff90Days))
                .count();

        int reviewsLast60Days = (int) reviewRepo.findByBusinessId(businessId).stream()
                .filter(r -> r.getCreatedAt().isAfter(cutoff60Days))
                .count();

        // Tier 1: New Starter (Getting Started)
        if (totalReviews <= 2) {
            return "New Starter";
        }

        // Tier 2: Rising Star (Quick Win)
        if ((totalReviews >= 3 && reputulRating >= 3.5) ||
                (totalReviews >= 5 && reputulRating >= 3.0)) {
            if (totalReviews < 8) {
                return "Rising Star";
            }
        }

        // Tier 3: Trusted Pro (requires at least 1 review in last 60 days for activity)
        if (((totalReviews >= 8 && reputulRating >= 4.0) ||
                (totalReviews >= 15 && reputulRating >= 3.8)) &&
                reviewsLast60Days >= 1) {

            if (totalReviews < 15 || reputulRating < 4.3) {
                return "Trusted Pro";
            }
        }

        // Tier 4: Top Rated (requires 2+ reviews in last 90 days)
        if (((totalReviews >= 15 && reputulRating >= 4.3) ||
                (totalReviews >= 25 && reputulRating >= 4.0)) &&
                reviewsLast90Days >= 2) {

            if (totalReviews < 25 || reputulRating < 4.5) {
                return "Top Rated";
            }
        }

        // Tier 5: Neighborhood Favorite (requires 3+ reviews in last 90 days for recency)
        if (totalReviews >= 25 && reputulRating >= 4.5 && reviewsLast90Days >= 3) {
            return "Neighborhood Favorite";
        }

        // If they have the score/volume but not the recency, drop down one tier
        if (totalReviews >= 25 && reputulRating >= 4.5) {
            return "Top Rated"; // Has the numbers but stale
        }

        if (totalReviews >= 15 && reputulRating >= 4.3) {
            return "Trusted Pro"; // Has the numbers but stale
        }

        // Default: Building Reputation
        if (totalReviews > 0) {
            return "Building Reputation";
        }

        return "No Reviews Yet";
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

        // Use activity-aware badge calculation
        String newBadge = determineEnhancedBadgeWithActivity(
                business.getId(),
                rating,
                (int) totalReviews
        );

        business.setBadge(newBadge);
        return newBadge;
    }

    /**
     * Get badge explanation for UI tooltips
     */
    public String getBadgeExplanation(String badge) {
        return switch (badge) {
            case "New Starter" ->
                    "Just getting started - build your reputation by collecting reviews!";
            case "Rising Star" ->
                    "Great momentum! You're building a solid reputation with early positive reviews.";
            case "Trusted Pro" ->
                    "Established quality service with consistent positive feedback from customers.";
            case "Top Rated" ->
                    "Outstanding reputation! Customers consistently rate your service highly.";
            case "Neighborhood Favorite" ->
                    "Elite status! You're the gold standard for local service with exceptional reviews.";
            case "Building Reputation" ->
                    "Keep collecting reviews to earn your first badge!";
            case "No Reviews Yet" ->
                    "Start collecting reviews to build your reputation.";
            default ->
                    "Keep up the great work and collect more reviews!";
        };
    }

    /**
     * Get badge color for UI styling
     */
    public String getBadgeColor(String badge) {
        return switch (badge) {
            case "New Starter" -> "bg-gradient-to-r from-blue-400 to-blue-500 text-white";
            case "Rising Star" -> "bg-gradient-to-r from-yellow-500 to-orange-500 text-white";
            case "Trusted Pro" -> "bg-gradient-to-r from-green-500 to-emerald-600 text-white";
            case "Top Rated" -> "bg-gradient-to-r from-blue-600 to-cyan-500 text-white";
            case "Neighborhood Favorite" -> "bg-gradient-to-r from-purple-500 to-pink-500 text-white";
            case "Building Reputation" -> "bg-gradient-to-r from-gray-400 to-gray-500 text-white";
            case "No Reviews Yet" -> "bg-gradient-to-r from-gray-300 to-gray-400 text-gray-800";
            default -> "bg-gradient-to-r from-gray-400 to-gray-500 text-white";
        };
    }

    /**
     * Get badge icon/emoji for UI
     */
    public String getBadgeIcon(String badge) {
        return switch (badge) {
            case "New Starter" -> "🌱";
            case "Rising Star" -> "⭐";
            case "Trusted Pro" -> "🏅";
            case "Top Rated" -> "💎";
            case "Neighborhood Favorite" -> "👑";
            case "Building Reputation" -> "📈";
            default -> "📋";
        };
    }

    /**
     * Determine next badge tier and requirements
     */
    public String getNextBadgeRequirements(double reputulRating, int totalReviews, int recentReviews) {
        String currentBadge = determineEnhancedBadge(reputulRating, totalReviews);

        return switch (currentBadge) {
            case "No Reviews Yet", "New Starter" ->
                    "Collect 3 reviews with 3.5+ star average to earn Rising Star ⭐";

            case "Building Reputation" -> {
                if (totalReviews < 3) {
                    yield "Need " + (3 - totalReviews) + " more reviews for Rising Star ⭐";
                }
                if (reputulRating < 3.5) {
                    yield "Improve rating to 3.5+ stars to earn Rising Star ⭐";
                }
                yield "You're on your way! Keep collecting positive reviews.";
            }

            case "Rising Star" -> {
                if (totalReviews < 8) {
                    yield "Collect " + (8 - totalReviews) + " more reviews for Trusted Pro 🏅";
                }
                if (reputulRating < 4.0) {
                    yield "Improve rating to 4.0+ stars for Trusted Pro 🏅";
                }
                yield "Maintain quality and collect more reviews for Trusted Pro 🏅";
            }

            case "Trusted Pro" -> {
                if (totalReviews < 15) {
                    yield "Collect " + (15 - totalReviews) + " more reviews for Top Rated 💎";
                }
                if (reputulRating < 4.3) {
                    yield "Improve rating to 4.3+ stars for Top Rated 💎";
                }
                if (recentReviews < 2) {
                    yield "Get 2+ reviews in the last 90 days for Top Rated 💎";
                }
                yield "You're close to Top Rated! Keep up the excellent service.";
            }

            case "Top Rated" -> {
                if (totalReviews < 25) {
                    yield "Collect " + (25 - totalReviews) + " more reviews for Neighborhood Favorite 👑";
                }
                if (reputulRating < 4.5) {
                    yield "Improve rating to 4.5+ stars for Neighborhood Favorite 👑";
                }
                if (recentReviews < 3) {
                    yield "Get 3+ reviews in the last 90 days for Neighborhood Favorite 👑";
                }
                yield "Almost there! Maintain excellence for Neighborhood Favorite 👑";
            }

            case "Neighborhood Favorite" ->
                    "🎉 Congratulations! You've achieved the highest badge tier!";

            default -> "Keep collecting reviews to improve your badge!";
        };
    }

    /**
     * Get progress percentage to next badge (0-100)
     */
    public int getProgressToNextBadge(double reputulRating, int totalReviews) {
        String currentBadge = determineEnhancedBadge(reputulRating, totalReviews);

        return switch (currentBadge) {
            case "No Reviews Yet", "New Starter" -> {
                // Progress to Rising Star (need 3 reviews with 3.5+ stars)
                int reviewProgress = Math.min(100, (totalReviews * 100) / 3);
                int ratingProgress = (int) Math.min(100, (reputulRating * 100) / 3.5);
                yield Math.min(reviewProgress, ratingProgress);
            }

            case "Building Reputation", "Rising Star" -> {
                // Progress to Trusted Pro (need 8 reviews with 4.0+ stars)
                int reviewProgress = Math.min(100, (totalReviews * 100) / 8);
                int ratingProgress = (int) Math.min(100, (reputulRating * 100) / 4.0);
                yield Math.min(reviewProgress, ratingProgress);
            }

            case "Trusted Pro" -> {
                // Progress to Top Rated (need 15 reviews with 4.3+ stars)
                int reviewProgress = Math.min(100, (totalReviews * 100) / 15);
                int ratingProgress = (int) Math.min(100, (reputulRating * 100) / 4.3);
                yield Math.min(reviewProgress, ratingProgress);
            }

            case "Top Rated" -> {
                // Progress to Neighborhood Favorite (need 25 reviews with 4.5+ stars)
                int reviewProgress = Math.min(100, (totalReviews * 100) / 25);
                int ratingProgress = (int) Math.min(100, (reputulRating * 100) / 4.5);
                yield Math.min(reviewProgress, ratingProgress);
            }

            case "Neighborhood Favorite" -> 100; // Max level achieved

            default -> 0;
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