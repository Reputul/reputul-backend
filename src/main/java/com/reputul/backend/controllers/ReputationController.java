package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReputationService;
import com.reputul.backend.services.BadgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

/**
 * Enhanced Reputation Controller with Wilson Score endpoints
 */
@RestController
@RequestMapping("/api/reputation")
@Slf4j
public class ReputationController {

    private final ReputationService reputationService;
    private final BadgeService badgeService;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    public ReputationController(
            ReputationService reputationService,
            BadgeService badgeService,
            BusinessRepository businessRepository,
            UserRepository userRepository
    ) {
        this.reputationService = reputationService;
        this.badgeService = badgeService;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    /**
     * BACKWARD COMPATIBILITY: Keep existing endpoint
     * GET /api/reputation/business/{businessId}
     * Now returns Wilson-based composite score
     */
    @GetMapping("/business/{businessId}")
    public double getScore(@PathVariable Long businessId) {
        return reputationService.getReputationScore(businessId);
    }

    /**
     * NEW: Get comprehensive reputation metrics for business owner dashboard
     * GET /api/reputation/business/{businessId}/detailed
     */
    @GetMapping("/business/{businessId}/detailed")
    public ResponseEntity<ReputationService.ReputationBreakdown> getDetailedReputationMetrics(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Verify user owns this business
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepository.findByIdAndUser(businessId, user)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            ReputationService.ReputationBreakdown breakdown = reputationService.getReputationBreakdown(businessId);

            return ResponseEntity.ok(breakdown);
        } catch (Exception e) {
            log.error("Error getting detailed reputation metrics for business {}: {}", businessId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * NEW: Get public reputation data for widgets (no authentication required)
     * GET /api/reputation/public/{businessId}
     */
    @GetMapping("/public/{businessId}")
    public ResponseEntity<Map<String, Object>> getPublicReputationData(@PathVariable Long businessId) {
        try {
            Business business = businessRepository.findById(businessId)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            double reputulRating = reputationService.calculateReputulRating(businessId);
            String badge = business.getBadge();

            Map<String, Object> publicData = new HashMap<>();
            publicData.put("businessId", businessId);
            publicData.put("businessName", business.getName());
            publicData.put("reputulRating", reputulRating);
            publicData.put("formattedRating", String.format("%.1f", reputulRating));
            publicData.put("badge", badge != null ? badge : "Unranked");
            publicData.put("badgeColor", badgeService.getBadgeColor(badge));
            publicData.put("badgeExplanation", badgeService.getBadgeExplanation(badge));
            publicData.put("howWeCalculate", "Based on review quality, volume, and recency using Wilson Score confidence intervals");

            return ResponseEntity.ok(publicData);
        } catch (Exception e) {
            log.error("Error getting public reputation data for business {}: {}", businessId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * NEW: Get reputation metrics for all user's businesses (dashboard overview)
     * GET /api/reputation/dashboard
     */
    @GetMapping("/dashboard")
    public ResponseEntity<List<Map<String, Object>>> getDashboardReputationMetrics(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<Business> businesses = businessRepository.findByUser(user);

            List<Map<String, Object>> allMetrics = businesses.stream()
                    .map(business -> {
                        ReputationService.ReputationBreakdown breakdown = reputationService.getReputationBreakdown(business.getId());
                        String badge = badgeService.determineEnhancedBadge(breakdown.reputulRating, breakdown.totalReviews);

                        Map<String, Object> metrics = new HashMap<>();
                        metrics.put("businessId", business.getId());
                        metrics.put("businessName", business.getName());
                        metrics.put("industry", business.getIndustry());
                        metrics.put("reputulRating", breakdown.reputulRating);
                        metrics.put("formattedRating", String.format("%.1f", breakdown.reputulRating));
                        metrics.put("compositeScore", breakdown.compositeScore);
                        metrics.put("qualityScore", breakdown.qualityScore);
                        metrics.put("velocityScore", breakdown.velocityScore);
                        metrics.put("responsivenessScore", breakdown.responsivenessScore);
                        metrics.put("totalReviews", breakdown.totalReviews);
                        metrics.put("positiveReviews", breakdown.positiveReviews);
                        metrics.put("reviewsLast90d", breakdown.reviewsLast90d);
                        metrics.put("badge", badge);
                        metrics.put("colorBand", breakdown.getColorBand());

                        return metrics;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(allMetrics);
        } catch (Exception e) {
            log.error("Error getting dashboard reputation metrics: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * NEW: Manually trigger reputation recalculation for a business
     * POST /api/reputation/business/{businessId}/recalculate
     */
    @PostMapping("/business/{businessId}/recalculate")
    public ResponseEntity<Map<String, Object>> recalculateReputationMetrics(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Verify user owns this business
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepository.findByIdAndUser(businessId, user)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            // Trigger recalculation
            reputationService.updateBusinessReputationAndBadge(businessId);

            // Return updated metrics
            ReputationService.ReputationBreakdown breakdown = reputationService.getReputationBreakdown(businessId);
            String badge = badgeService.determineEnhancedBadge(breakdown.reputulRating, breakdown.totalReviews);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Reputation metrics recalculated successfully");
            response.put("reputulRating", breakdown.reputulRating);
            response.put("compositeScore", breakdown.compositeScore);
            response.put("badge", badge);
            response.put("breakdown", breakdown);

            log.info("Manual reputation recalculation completed for business {}", businessId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error recalculating reputation metrics for business {}: {}", businessId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * NEW: Get badge requirements and improvement suggestions
     * GET /api/reputation/business/{businessId}/improvement-suggestions
     */
    @GetMapping("/business/{businessId}/improvement-suggestions")
    public ResponseEntity<Map<String, Object>> getImprovementSuggestions(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            // Verify user owns this business
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Business business = businessRepository.findByIdAndUser(businessId, user)
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            ReputationService.ReputationBreakdown breakdown = reputationService.getReputationBreakdown(businessId);
            String currentBadge = badgeService.determineEnhancedBadge(breakdown.reputulRating, breakdown.totalReviews);
            String nextRequirements = badgeService.getNextBadgeRequirements(breakdown.reputulRating, breakdown.totalReviews, breakdown.reviewsLast90d);
            String badgeExplanation = badgeService.getBadgeExplanation(currentBadge);

            // Generate improvement suggestions based on lowest scores
            List<String> suggestions = generateImprovementSuggestions(breakdown);

            Map<String, Object> response = new HashMap<>();
            response.put("currentBadge", currentBadge);
            response.put("badgeExplanation", badgeExplanation);
            response.put("nextRequirements", nextRequirements);
            response.put("colorBand", breakdown.getColorBand());
            response.put("improvementSuggestions", suggestions);
            response.put("breakdown", breakdown);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting improvement suggestions for business {}: {}", businessId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * NEW: Get algorithm explanation for transparency
     * GET /api/reputation/explanation
     */
    @GetMapping("/explanation")
    public ResponseEntity<Map<String, Object>> getAlgorithmExplanation() {
        Map<String, Object> explanation = new HashMap<>();

        Map<String, Object> reputulRating = new HashMap<>();
        reputulRating.put("description", "Public-facing star rating (0-5) using Wilson Score confidence intervals");
        reputulRating.put("factors", List.of("Positive review share (4-5 stars)", "Review volume confidence", "Recency weighting"));
        reputulRating.put("benefit", "Prevents small businesses with few perfect reviews from outranking established businesses");
        reputulRating.put("publicDisplay", "Shows as stars on public profiles and embeddable widgets");

        Map<String, Object> reputationScore = new HashMap<>();
        reputationScore.put("description", "Owner-facing composite score (0-100) for improvement guidance");
        Map<String, Object> components = new HashMap<>();
        components.put("quality", "60% - Based on Wilson Score positive review confidence");
        components.put("velocity", "25% - Review gathering pace and recency mix");
        components.put("responsiveness", "15% - Response rate and speed to customer reviews");
        reputationScore.put("components", components);

        Map<String, Object> colorBands = new HashMap<>();
        colorBands.put("red", "0-45 - Needs attention");
        colorBands.put("yellow", "46-75 - Good progress");
        colorBands.put("green", "76-100 - Excellent performance");
        reputationScore.put("colorBands", colorBands);

        Map<String, Object> badges = new HashMap<>();
        Map<String, Object> criteria = new HashMap<>();
        criteria.put("Neighborhood Favorite", "4.5+ Wilson Score, 90+ composite score, 20+ reviews, recent activity");
        criteria.put("Top Rated", "4.0+ Wilson Score, 80+ composite score, 10+ reviews, recent activity");
        criteria.put("Rising Star", "3.5+ Wilson Score, 60+ composite score, 5+ reviews, recent momentum");
        badges.put("criteria", criteria);

        explanation.put("reputulRating", reputulRating);
        explanation.put("reputationScore", reputationScore);
        explanation.put("badges", badges);
        explanation.put("competitiveAdvantage", "Wilson Score prevents manipulation and provides fairer ratings than simple averages used by competitors");

        return ResponseEntity.ok(explanation);
    }

    /**
     * Helper method to generate improvement suggestions
     */
    private List<String> generateImprovementSuggestions(ReputationService.ReputationBreakdown breakdown) {
        List<String> suggestions = new java.util.ArrayList<>();

        if (breakdown.qualityScore < 60) {
            suggestions.add("Focus on service quality - encourage more positive reviews through excellent customer experiences");
        }

        if (breakdown.velocityScore < 50) {
            suggestions.add("Increase review gathering pace - send more review requests to recent customers");
        }

        if (breakdown.reviewsLast90d == 0) {
            suggestions.add("Get recent reviews - your rating benefits from fresh customer feedback");
        }

        if (breakdown.totalReviews < 10) {
            suggestions.add("Build review volume - aim for " + (10 - breakdown.totalReviews) + " more reviews to improve confidence");
        }

        if (breakdown.responsivenessScore < 50) {
            suggestions.add("Improve response time - respond to customer reviews quickly to boost responsiveness score");
        }

        if (suggestions.isEmpty()) {
            suggestions.add("Great work! Continue providing excellent service to maintain your strong reputation");
        }

        return suggestions;
    }
}