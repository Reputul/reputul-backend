package com.reputul.backend.repositories;

import com.reputul.backend.models.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    // ORGANIZATION-AWARE QUERIES - ADDED FOR TENANCY SUPPORT
    // ================================================================

    /**
     * ADDED: Get reviews for a business with organization verification (tenant scoping)
     * This ensures users can only see reviews for businesses in their organization
     * Used by: ReviewController.getReviewsByBusiness() with organization check
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId")
    List<Review> findByBusinessIdAndOrganizationId(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId,
            Sort sort);

    /**
     * ADDED: Get reviews for a business with organization verification (no sorting)
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId")
    List<Review> findByBusinessIdAndOrganizationId(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId);

    /**
     * ADDED: Get reviews for a business with organization verification (ordered by creation date)
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndOrganizationIdOrderByCreatedAtDesc(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId);

    /**
     * ADDED: Find review by ID with organization verification (tenant scoping)
     * Critical for security - ensures users can only access reviews from their organization
     */
    @Query("SELECT r FROM Review r WHERE r.id = :reviewId AND r.business.organization.id = :organizationId")
    Optional<Review> findByIdAndOrganizationId(
            @Param("reviewId") Long reviewId,
            @Param("organizationId") Long organizationId);

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
     * EXISTING: Count total reviews for a business
     * Used by: BusinessController.getReviewSummary() and reputation calculations
     */
    long countByBusinessId(Long businessId);

    /**
     * ADDED: Get average rating for a business with organization verification
     */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId")
    Double findAverageRatingByBusinessIdAndOrganizationId(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId);

    /**
     * ADDED: Count reviews for a business with organization verification
     */
    @Query("SELECT COUNT(r) FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId")
    long countByBusinessIdAndOrganizationId(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId);

    /**
     * Find reviews by business ID with minimum rating filter
     * Used by: WidgetService for filtering reviews in widgets
     */
    List<Review> findByBusinessIdAndRatingGreaterThanEqual(Long businessId, Integer minRating);

    // ================================================================
    // TIME-BASED QUERIES - Used by analytics and reputation calculations
    // ================================================================

    /**
     * EXISTING: Get reviews for a business within a date range
     * Used by: ReputationService for time-based calculations
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.createdAt >= :startDate AND r.createdAt <= :endDate ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndCreatedAtBetween(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * ADDED: Get reviews for a business within a date range with organization verification
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId AND r.createdAt >= :startDate AND r.createdAt <= :endDate ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndOrganizationIdAndCreatedAtBetween(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * EXISTING: Get reviews after a specific date
     * Used by: ReputationService for recent activity calculations
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.createdAt >= :afterDate ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndCreatedAtAfter(
            @Param("businessId") Long businessId,
            @Param("afterDate") OffsetDateTime afterDate
    );

    /**
     * ADDED: Get reviews after a specific date with organization verification
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId AND r.createdAt >= :afterDate ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndOrganizationIdAndCreatedAtAfter(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId,
            @Param("afterDate") OffsetDateTime afterDate
    );

    // ================================================================
    // RATING-BASED QUERIES - Used by analytics
    // ================================================================

    /**
     * EXISTING: Find reviews by rating range
     * Used by: Analytics for rating distribution
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.rating >= :minRating AND r.rating <= :maxRating ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndRatingBetween(
            @Param("businessId") Long businessId,
            @Param("minRating") Integer minRating,
            @Param("maxRating") Integer maxRating
    );

    /**
     * ADDED: Find reviews by rating range with organization verification
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId AND r.rating >= :minRating AND r.rating <= :maxRating ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndOrganizationIdAndRatingBetween(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId,
            @Param("minRating") Integer minRating,
            @Param("maxRating") Integer maxRating
    );

    // ================================================================
    // SOURCE-BASED QUERIES - Used by integration tracking
    // ================================================================

    /**
     * EXISTING: Find reviews by source (Google, Facebook, etc.)
     * Used by: Integration services to avoid duplicates
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.source = :source ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndSource(
            @Param("businessId") Long businessId,
            @Param("source") String source
    );

    /**
     * EXISTING: Find review by source and external ID (for deduplication)
     * Used by: Integration services to avoid importing duplicates
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.source = :source AND r.sourceReviewId = :sourceReviewId")
    Optional<Review> findByBusinessIdAndSourceAndSourceReviewId(
            @Param("businessId") Long businessId,
            @Param("source") String source,
            @Param("sourceReviewId") String sourceReviewId
    );

    /**
     * ADDED: Find reviews by source with organization verification
     */
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.business.organization.id = :organizationId AND r.source = :source ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndOrganizationIdAndSource(
            @Param("businessId") Long businessId,
            @Param("organizationId") Long organizationId,
            @Param("source") String source
    );

    // ================================================================
    // ORGANIZATION-LEVEL QUERIES - Used by dashboards and analytics
    // ================================================================

    /**
     * ADDED: Get all reviews for all businesses in an organization
     * Used by: Organization-level dashboards and analytics
     */
    @Query("SELECT r FROM Review r WHERE r.business.organization.id = :organizationId ORDER BY r.createdAt DESC")
    List<Review> findByOrganizationIdOrderByCreatedAtDesc(@Param("organizationId") Long organizationId);

    /**
     * ADDED: Count all reviews for an organization
     * Used by: Organization-level metrics
     */
    @Query("SELECT COUNT(r) FROM Review r WHERE r.business.organization.id = :organizationId")
    long countByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * ADDED: Get average rating across all businesses in an organization
     * Used by: Organization-level analytics
     */
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.organization.id = :organizationId")
    Double findAverageRatingByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * ADDED: Get reviews for organization within date range
     * Used by: Organization-level reporting
     */
    @Query("SELECT r FROM Review r WHERE r.business.organization.id = :organizationId AND r.createdAt >= :startDate AND r.createdAt <= :endDate ORDER BY r.createdAt DESC")
    List<Review> findByOrganizationIdAndCreatedAtBetween(
            @Param("organizationId") Long organizationId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

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

    // ================================================================
    // ADDITIONAL TIME-BASED QUERIES - Used by Analytics and Reports
    // ================================================================

    /**
     * EXISTING: Get reviews after a specific date with ordering
     * Used by: Analytics services for time-based filtering
     */
    List<Review> findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long businessId,
            OffsetDateTime afterDate
    );

    /**
     * EXISTING: Get reviews before a specific date
     * Used by: Historical analysis
     */
    List<Review> findByBusinessIdAndCreatedAtBeforeOrderByCreatedAtDesc(
            Long businessId,
            OffsetDateTime beforeDate
    );

    /**
     * Get review counts by source for a business
     * Used by: Source analytics
     */
    @Query("SELECT r.source, COUNT(r) FROM Review r WHERE r.business.id = :businessId GROUP BY r.source")
    List<Object[]> countByBusinessIdGroupBySource(@Param("businessId") Long businessId);

    /**
     * Get rating distribution for a business (for charts/analytics)
     * Returns: [[rating, count], [rating, count], ...]
     */
    @Query("SELECT r.rating, COUNT(r) FROM Review r WHERE r.business.id = :businessId GROUP BY r.rating ORDER BY r.rating DESC")
    List<Object[]> countByBusinessIdGroupByRating(@Param("businessId") Long businessId);

    /**
     * Get reviews by source across all businesses (for admin)
     */
    List<Review> findBySourceOrderByCreatedAtDesc(String source);

    /**
     * Count reviews by source for a business
     */
    long countByBusinessIdAndSource(Long businessId, String source);

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
     * Find reviews by organization ID (for Zapier trigger)
     */
    Page<Review> findByBusinessOrganizationId(Long organizationId, Pageable pageable);
}