package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for business entities with comprehensive querying capabilities
 * for multi-tenant business management
 */
@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    /**
     * Find business by ID and user ID (tenant scoping - security critical)
     * This ensures users can only access their own businesses
     */
    @Query("SELECT b FROM Business b WHERE b.id = :id AND b.user.id = :userId")
    Optional<Business> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * Find the first business for a user (primary business)
     * Used in StripeService for getting primary business
     * Ordered by creation date to get the first business created
     */
    Optional<Business> findFirstByUserOrderByCreatedAtAsc(User user);

    /**
     * Find all businesses for a user (tenant scoping)
     */
    List<Business> findByUser(User user);

    /**
     * Find all businesses for a user by user ID
     */
    @Query("SELECT b FROM Business b WHERE b.user.id = :userId ORDER BY b.createdAt ASC")
    List<Business> findByUserIdOrderByCreatedAtAsc(@Param("userId") Long userId);

    /**
     * Alternative method name for findByUserIdOrderByCreatedAtAsc
     */
    default List<Business> findByUserId(Long userId) {
        return findByUserIdOrderByCreatedAtAsc(userId);
    }

    /**
     * Find business by ID and user (alternative method name)
     */
    @Query("SELECT b FROM Business b WHERE b.id = :id AND b.user = :user")
    Optional<Business> findByIdAndUser(@Param("id") Long id, @Param("user") User user);

    /**
     * Check if business exists by ID and user
     */
    @Query("SELECT COUNT(b) > 0 FROM Business b WHERE b.id = :id AND b.user = :user")
    boolean existsByIdAndUser(@Param("id") Long id, @Param("user") User user);

    /**
     * Find businesses by user with review platforms configured
     * FIXED: Added the missing reviewPlatformsConfigured condition
     */
    @Query("SELECT b FROM Business b WHERE b.user = :user AND b.reviewPlatformsConfigured = :configured")
    List<Business> findByUserAndReviewPlatformsConfigured(@Param("user") User user, @Param("configured") boolean configured);

    /**
     * Count businesses by user with review platforms configured
     * FIXED: Added the missing reviewPlatformsConfigured condition
     */
    @Query("SELECT COUNT(b) FROM Business b WHERE b.user = :user AND b.reviewPlatformsConfigured = :configured")
    long countByUserAndReviewPlatformsConfigured(@Param("user") User user, @Param("configured") boolean configured);

    /**
     * Find businesses by industry (case insensitive)
     */
    @Query("SELECT b FROM Business b WHERE LOWER(b.industry) = LOWER(:industry)")
    List<Business> findByIndustryIgnoreCase(@Param("industry") String industry);

    /**
     * Find businesses by name containing (case insensitive)
     */
    @Query("SELECT b FROM Business b WHERE LOWER(b.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    List<Business> findByNameContainingIgnoreCase(@Param("name") String name);

    /**
     * Find business by name and user (prevent duplicate business names per user)
     */
    Optional<Business> findByNameAndUser(String name, User user);

    /**
     * Find business by Google Place ID (for Google integration)
     */
    Optional<Business> findByGooglePlaceId(String googlePlaceId);

    /**
     * Find business by Google Place ID and user (tenant scoped)
     */
    @Query("SELECT b FROM Business b WHERE b.googlePlaceId = :googlePlaceId AND b.user.id = :userId")
    Optional<Business> findByGooglePlaceIdAndUserId(@Param("googlePlaceId") String googlePlaceId, @Param("userId") Long userId);

    /**
     * Count businesses for a user (for plan limits)
     */
    long countByUser(User user);

    /**
     * Count businesses for a user by user ID
     */
    @Query("SELECT COUNT(b) FROM Business b WHERE b.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    /**
     * Find businesses by industry (for analytics)
     */
    List<Business> findByIndustry(String industry);

    /**
     * Find businesses with phone number (for SMS campaigns)
     */
    @Query("SELECT b FROM Business b WHERE b.phone IS NOT NULL AND b.phone != ''")
    List<Business> findBusinessesWithPhone();

    /**
     * Find businesses by user and phone number
     */
    @Query("SELECT b FROM Business b WHERE b.user.id = :userId AND b.phone = :phone")
    Optional<Business> findByUserIdAndPhone(@Param("userId") Long userId, @Param("phone") String phone);

    /**
     * Find businesses with active subscriptions (join with subscription)
     */
    @Query("SELECT DISTINCT b FROM Business b JOIN Subscription s ON b.id = s.business.id WHERE s.status IN ('ACTIVE', 'TRIALING')")
    List<Business> findBusinessesWithActiveSubscriptions();

    /**
     * Find businesses by reputation score range (for badge eligibility)
     */
    @Query("SELECT b FROM Business b WHERE b.reputationScore BETWEEN :minScore AND :maxScore")
    List<Business> findByReputationScoreBetween(@Param("minScore") Double minScore, @Param("maxScore") Double maxScore);

    /**
     * Find businesses above reputation score threshold
     */
    @Query("SELECT b FROM Business b WHERE b.reputationScore >= :minScore")
    List<Business> findByReputationScoreGreaterThanEqual(@Param("minScore") Double minScore);

    /**
     * Find businesses needing reputation score calculation
     */
    @Query("SELECT b FROM Business b WHERE b.reputationScore IS NULL OR b.updatedAt < :cutoffDate")
    List<Business> findBusinessesNeedingReputationUpdate(@Param("cutoffDate") java.time.OffsetDateTime cutoffDate);

    /**
     * Find businesses by address components (for location-based features)
     */
    @Query("SELECT b FROM Business b WHERE LOWER(b.address) LIKE LOWER(CONCAT('%', :location, '%'))")
    List<Business> findByAddressContainingIgnoreCase(@Param("location") String location);

    /**
     * Find businesses with website (for integration opportunities)
     */
    @Query("SELECT b FROM Business b WHERE b.website IS NOT NULL AND b.website != ''")
    List<Business> findBusinessesWithWebsite();

    /**
     * Custom query to find businesses with recent activity (for engagement analytics)
     */
    @Query("""
        SELECT b FROM Business b 
        WHERE EXISTS (
            SELECT 1 FROM ReviewRequest rr 
            WHERE rr.business = b 
            AND rr.createdAt > :cutoffDate
        ) 
        OR EXISTS (
            SELECT 1 FROM Review r 
            WHERE r.business = b 
            AND r.createdAt > :cutoffDate
        )
        """)
    List<Business> findBusinessesWithRecentActivity(@Param("cutoffDate") java.time.OffsetDateTime cutoffDate);

    /**
     * Find businesses by user email (for admin/support queries)
     */
    @Query("SELECT b FROM Business b WHERE b.user.email = :email")
    List<Business> findByUserEmail(@Param("email") String email);
}