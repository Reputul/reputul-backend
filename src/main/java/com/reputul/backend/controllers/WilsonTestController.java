package com.reputul.backend.controllers;

import com.reputul.backend.services.WilsonScoreService;
import com.reputul.backend.services.WilsonScoreService.ComparisonResult;
import com.reputul.backend.services.WilsonScoreService.DetailedResult;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.models.Business;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;

/**
 * Test Controller to see Wilson Score in action with your real data!
 * This will show you immediately how much fairer the new algorithm is
 */
@RestController
@RequestMapping("/api/v1/test")
public class WilsonTestController {

    private final WilsonScoreService wilsonScoreService;
    private final BusinessRepository businessRepository;

    public WilsonTestController(WilsonScoreService wilsonScoreService,
                                BusinessRepository businessRepository) {
        this.wilsonScoreService = wilsonScoreService;
        this.businessRepository = businessRepository;
    }

    /**
     * Test Wilson Score for a specific business
     * GET /api/test/wilson/{businessId}
     */
    @GetMapping("/wilson/{businessId}")
    public ResponseEntity<Map<String, Object>> testWilsonScore(@PathVariable Long businessId) {
        try {
            Business business = businessRepository.findById(businessId).orElse(null);
            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            ComparisonResult result = wilsonScoreService.compareRatings(businessId);
            DetailedResult details = wilsonScoreService.getDetailedAnalysis(businessId);

            Map<String, Object> response = new HashMap<>();
            response.put("business", Map.of(
                    "id", business.getId(),
                    "name", business.getName(),
                    "industry", business.getIndustry()
            ));
            response.put("comparison", result);
            response.put("details", details);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * See Wilson Score vs Simple Average for ALL your businesses
     * GET /api/test/wilson-all
     *
     * This will show you the dramatic differences!
     */
    @GetMapping("/wilson-all")
    public ResponseEntity<List<BusinessComparison>> testAllBusinesses() {
        try {
            List<Business> businesses = businessRepository.findAll();

            List<BusinessComparison> comparisons = businesses.stream()
                    .map(business -> {
                        ComparisonResult result = wilsonScoreService.compareRatings(business.getId());
                        return new BusinessComparison(
                                business.getId(),
                                business.getName(),
                                business.getIndustry(),
                                result.reviewCount,
                                result.oldRating,
                                result.newRating,
                                result.analysis
                        );
                    })
                    .filter(comp -> comp.reviewCount > 0) // Only businesses with reviews
                    .sorted((a, b) -> Integer.compare(b.reviewCount, a.reviewCount)) // Sort by review count desc
                    .collect(Collectors.toList());

            return ResponseEntity.ok(comparisons);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Find businesses where Wilson Score makes the BIGGEST difference
     * GET /api/test/wilson-biggest-differences
     *
     * This will show you the most dramatic examples of small-sample bias correction
     */
    @GetMapping("/wilson-biggest-differences")
    public ResponseEntity<List<BusinessComparison>> findBiggestDifferences() {
        try {
            List<Business> businesses = businessRepository.findAll();

            List<BusinessComparison> comparisons = businesses.stream()
                    .map(business -> {
                        ComparisonResult result = wilsonScoreService.compareRatings(business.getId());
                        return new BusinessComparison(
                                business.getId(),
                                business.getName(),
                                business.getIndustry(),
                                result.reviewCount,
                                result.oldRating,
                                result.newRating,
                                result.analysis
                        );
                    })
                    .filter(comp -> comp.reviewCount > 0)
                    .sorted((a, b) -> Double.compare(
                            Math.abs(b.difference),
                            Math.abs(a.difference)
                    )) // Sort by biggest absolute difference
                    .limit(20) // Top 20 biggest differences
                    .collect(Collectors.toList());

            return ResponseEntity.ok(comparisons);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Find small businesses that benefit most from Wilson Score
     * GET /api/test/small-business-winners
     */
    @GetMapping("/small-business-winners")
    public ResponseEntity<List<BusinessComparison>> findSmallBusinessWinners() {
        try {
            List<Business> businesses = businessRepository.findAll();

            List<BusinessComparison> winners = businesses.stream()
                    .map(business -> {
                        ComparisonResult result = wilsonScoreService.compareRatings(business.getId());
                        return new BusinessComparison(
                                business.getId(),
                                business.getName(),
                                business.getIndustry(),
                                result.reviewCount,
                                result.oldRating,
                                result.newRating,
                                result.analysis
                        );
                    })
                    .filter(comp -> comp.reviewCount <= 10 && comp.reviewCount > 0) // Small businesses
                    .filter(comp -> comp.difference > 0.2) // Wilson Score is significantly higher
                    .sorted((a, b) -> Double.compare(b.difference, a.difference))
                    .limit(10)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(winners);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Find businesses where simple average was inflated (Wilson Score is much lower)
     * GET /api/test/inflated-averages
     */
    @GetMapping("/inflated-averages")
    public ResponseEntity<List<BusinessComparison>> findInflatedAverages() {
        try {
            List<Business> businesses = businessRepository.findAll();

            List<BusinessComparison> inflated = businesses.stream()
                    .map(business -> {
                        ComparisonResult result = wilsonScoreService.compareRatings(business.getId());
                        return new BusinessComparison(
                                business.getId(),
                                business.getName(),
                                business.getIndustry(),
                                result.reviewCount,
                                result.oldRating,
                                result.newRating,
                                result.analysis
                        );
                    })
                    .filter(comp -> comp.reviewCount > 0 && comp.reviewCount < 20) // Small to medium sample
                    .filter(comp -> comp.difference < -0.3) // Wilson Score much lower
                    .sorted((a, b) -> Double.compare(a.difference, b.difference)) // Most negative first
                    .limit(15)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(inflated);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Summary statistics across all businesses
     * GET /api/test/wilson-summary
     */
    @GetMapping("/wilson-summary")
    public ResponseEntity<Map<String, Object>> getWilsonSummary() {
        try {
            List<Business> businesses = businessRepository.findAll();

            List<ComparisonResult> results = businesses.stream()
                    .map(business -> wilsonScoreService.compareRatings(business.getId()))
                    .filter(result -> result.reviewCount > 0)
                    .collect(Collectors.toList());

            if (results.isEmpty()) {
                return ResponseEntity.ok(Map.of("message", "No businesses with reviews found"));
            }

            // Calculate summary statistics
            double avgOldRating = results.stream().mapToDouble(r -> r.oldRating).average().orElse(0.0);
            double avgNewRating = results.stream().mapToDouble(r -> r.newRating).average().orElse(0.0);

            long businessesWithLowerWilson = results.stream()
                    .filter(r -> r.newRating < r.oldRating - 0.1)
                    .count();

            long businessesWithHigherWilson = results.stream()
                    .filter(r -> r.newRating > r.oldRating + 0.1)
                    .count();

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalBusinesses", results.size());
            summary.put("averageOldRating", Math.round(avgOldRating * 100.0) / 100.0);
            summary.put("averageNewRating", Math.round(avgNewRating * 100.0) / 100.0);
            summary.put("businessesWithLowerWilsonScore", businessesWithLowerWilson);
            summary.put("businessesWithHigherWilsonScore", businessesWithHigherWilson);
            summary.put("businessesUnchanged", results.size() - businessesWithLowerWilson - businessesWithHigherWilson);
            summary.put("analysis", String.format(
                    "Wilson Score corrected %d businesses downward (reducing small-sample bias) and %d upward (recent positive activity)",
                    businessesWithLowerWilson, businessesWithHigherWilson
            ));

            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Helper class for comparison display
     */
    public static class BusinessComparison {
        public final Long businessId;
        public final String businessName;
        public final String industry;
        public final int reviewCount;
        public final double oldRating;
        public final double newRating;
        public final double difference;
        public final String analysis;

        public BusinessComparison(Long businessId, String businessName, String industry, int reviewCount,
                                  double oldRating, double newRating, String analysis) {
            this.businessId = businessId;
            this.businessName = businessName;
            this.industry = industry;
            this.reviewCount = reviewCount;
            this.oldRating = oldRating;
            this.newRating = newRating;
            this.difference = Math.round((newRating - oldRating) * 100.0) / 100.0;
            this.analysis = analysis;
        }
    }
}

/*
🚀 TESTING COMMANDS - Try these immediately after starting your backend:

# Start your backend
./mvnw spring-boot:run

# See summary across all your businesses
curl http://localhost:8080/api/test/wilson-summary

# See all businesses comparison (sorted by review count)
curl http://localhost:8080/api/test/wilson-all

# Find the most dramatic differences (these will be eye-opening!)
curl http://localhost:8080/api/test/wilson-biggest-differences

# Find businesses with inflated simple averages
curl http://localhost:8080/api/test/inflated-averages

# Test a specific business
curl http://localhost:8080/api/test/wilson/1

WHAT YOU'LL LIKELY SEE:
✅ Small businesses with few reviews will have MUCH lower Wilson scores
✅ Large businesses with many reviews will have similar Wilson scores
✅ Some businesses will benefit from recency weighting
✅ Overall ratings will be more fair and trustworthy

This will immediately show you the value of the Wilson Score algorithm!
*/