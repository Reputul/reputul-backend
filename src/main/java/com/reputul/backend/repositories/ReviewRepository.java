package com.reputul.backend.repositories;

import com.reputul.backend.models.Review;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    // ================================================================
    // BASIC BUSINESS REVIEW QUERIES - Used by Controllers
    // ================================================================

    /**
     * Get all reviews for a business (no sorting)
     * Used by: Various controllers
     */
    List<Review> findByBusinessId(Long businessId);

    /**
     * Get reviews for a business with custom sorting
     * Used by: ReviewController.getReviewsByBusiness()
     * This is the STANDARD Spring Data JPA method - recommended over custom query
     */
    List<Review> findByBusinessId(Long businessId, Sort sort);

    /**
     * Get reviews for a business ordered by creation date (newest first)
     * Used by: Various controllers as default sorting
     */
    List<Review> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /**
     * EXISTING: Custom query for sorting (keep for backward compatibility)
     * Used by: ReviewController if you prefer custom queries
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId")
    List<Review> findByBusinessIdOrderBy(@Param("businessId") Long businessId, Sort sort);

    // ================================================================
    // BUSINESS ANALYTICS QUERIES - Used by BusinessController
    // ================================================================

    /**
     * EXISTING: Get the most recent review for a business
     * Used by: BusinessController.getReviewSummary()
     */
    List<Review> findTop1ByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /**
     * EXISTING: Get the average rating for a business
     * Used by: BusinessController.getReviewSummary()
     */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.id = :businessId")
    Double findAverageRatingByBusinessId(@Param("businessId") Long businessId);

    /**
     * EXISTING: Count total reviews for a business (changed from int to long)
     * Used by: BusinessController.getReviewSummary()
     */
    long countByBusinessId(Long businessId);

    /**
     * EXISTING: Get reviews by rating range for a business
     * Used by: Analytics and filtering
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.rating >= :minRating AND r.rating <= :maxRating ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndRatingBetween(
            @Param("businessId") Long businessId,
            @Param("minRating") int minRating,
            @Param("maxRating") int maxRating
    );

    /**
     * Get rating distribution for a business (for charts/analytics)
     * Returns: [[rating, count], [rating, count], ...]
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.business.id = :businessId GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> countByBusinessIdGroupByRating(@Param("businessId") Long businessId);

    // ================================================================
    // SOURCE-BASED QUERIES - Used by Review Management
    // ================================================================

    /**
     * EXISTING: Get reviews by source (manual, public, etc.)
     * Used by: Review source filtering
     */
    List<Review> findByBusinessIdAndSource(Long businessId, String source);

    /**
     * Get reviews by source across all businesses (for admin)
     */
    List<Review> findBySourceOrderByCreatedAtDesc(String source);

    /**
     * Count reviews by source for a business
     */
    long countByBusinessIdAndSource(Long businessId, String source);

    // ================================================================
    // CUSTOMER-SPECIFIC QUERIES - Used by Customer Management
    // ================================================================

    /**
     * Get all reviews by a specific customer
     * Used by: Customer profile pages, history tracking
     */
    List<Review> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * Count reviews by customer
     * Used by: Customer analytics
     */
    long countByCustomerId(Long customerId);

    /**
     * Get reviews by customer and business
     * Used by: Customer-business interaction history
     */
    List<Review> findByCustomerIdAndBusinessIdOrderByCreatedAtDesc(Long customerId, Long businessId);

    // ================================================================
    // FEEDBACK GATE QUERIES - Used by FeedbackGateService
    // ================================================================

    /**
     * Check if customer already used feedback gate
     * Used by: FeedbackGateService.hasCustomerUsedGate()
     */
    boolean existsByCustomerIdAndSource(Long customerId, String source);

    /**
     * Get most recent gate review for analytics
     * Used by: FeedbackGateService.getCustomerSentimentForAnalytics()
     */
    Optional<Review> findTopByCustomerIdAndSourceOrderByCreatedAtDesc(Long customerId, String source);

    /**
     * Alternative: Check using customer entity instead of ID
     */
    boolean existsByCustomerAndSource(com.reputul.backend.models.Customer customer, String source);

    /**
     * Alternative: Get latest review using customer entity
     */
    Optional<Review> findTopByCustomerAndSourceOrderByCreatedAtDesc(
            com.reputul.backend.models.Customer customer,
            String source
    );

    // ================================================================
    // DASHBOARD & ADMIN QUERIES - Used by Dashboards
    // ================================================================

    /**
     * EXISTING: Get recent reviews across all businesses (for dashboard)
     */
    @Query("SELECT r FROM Review r ORDER BY r.createdAt DESC")
    List<Review> findTop10ByOrderByCreatedAtDesc();

    /**
     * Get recent reviews for a specific user's businesses
     */
    @Query("SELECT r FROM Review r WHERE r.business.user.id = :userId ORDER BY r.createdAt DESC")
    List<Review> findTop10ByBusinessUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    /**
     * Get recent reviews since a specific date
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.createdAt >= :sinceDate ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(
            @Param("businessId") Long businessId,
            @Param("sinceDate") OffsetDateTime sinceDate
    );

    // ================================================================
    // ADVANCED ANALYTICS QUERIES - Used by Reports
    // ================================================================

    /**
     * Get average rating for multiple sources
     */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.id = :businessId AND r.source IN :sources")
    Double getAverageRatingByBusinessIdAndSources(
            @Param("businessId") Long businessId,
            @Param("sources") List<String> sources
    );

    /**
     * Get review counts by month for a business (for trend analysis)
     */
    @Query("SELECT YEAR(r.createdAt), MONTH(r.createdAt), COUNT(r) FROM Review r WHERE r.business.id = :businessId GROUP BY YEAR(r.createdAt), MONTH(r.createdAt) ORDER BY YEAR(r.createdAt) DESC, MONTH(r.createdAt) DESC")
    List<Object[]> getMonthlyReviewCounts(@Param("businessId") Long businessId);

    /**
     * Get high-rating reviews for promotion (4-5 stars)
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.rating >= 4 AND r.source != 'internal_feedback_gate' ORDER BY r.createdAt DESC")
    List<Review> findPositiveReviewsForBusiness(@Param("businessId") Long businessId);

    /**
     * Get reviews needing attention (1-3 stars from public sources)
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.rating <= 3 AND r.source IN ('public', 'manual') ORDER BY r.createdAt DESC")
    List<Review> findReviewsNeedingAttention(@Param("businessId") Long businessId);

    /**
     * Find review by business, source, and source review ID (for deduplication)
     */
    Optional<Review> findByBusinessIdAndSourceAndSourceReviewId(
            Long businessId,
            String source,
            String sourceReviewId
    );
}