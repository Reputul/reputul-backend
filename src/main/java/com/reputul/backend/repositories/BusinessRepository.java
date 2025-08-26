package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {

    List<Business> findByUserId(Long ownerId);
    Optional<Business> findByIdAndUserId(Long id, Long ownerId);

    // Find business by ID and owner (security check)
    Optional<Business> findByIdAndUser(Long id, User user);

    // Find all businesses for a specific owner
    List<Business> findByUser(User user);

    Business findFirstByUserOrderByCreatedAtAsc(User user);

    // Check if business exists and belongs to user
    boolean existsByIdAndUser(Long id, User user);

    // Find businesses by platform configuration status
    List<Business> findByUserAndReviewPlatformsConfigured(User user, Boolean reviewPlatformsConfigured);

    // Count businesses by platform configuration status
    long countByUserAndReviewPlatformsConfigured(User user, Boolean reviewPlatformsConfigured);

    // Find all businesses for owner ordered by creation date
    List<Business> findByUserOrderByCreatedAtDesc(User user);

    // ===== NEW METHODS FOR PUBLIC CONTROLLER =====

    /**
     * Find businesses by industry (case-insensitive)
     * Used by: GET /api/public/businesses/industry/{industry}
     */
    List<Business> findByIndustryIgnoreCase(String industry);

    /**
     * Search businesses by name containing text (case-insensitive)
     * Used by: GET /api/public/businesses/search?name={name}
     */
    List<Business> findByNameContainingIgnoreCase(String name);

    // ===== OPTIONAL: ADDITIONAL PUBLIC SEARCH METHODS =====

    /**
     * Find businesses by industry and order by reputation score descending
     * Useful for showing top-rated businesses in an industry
     */
    List<Business> findByIndustryIgnoreCaseOrderByReputationScoreDesc(String industry);

    /**
     * Find businesses with minimum reputation score
     * Useful for filtering quality businesses
     */
    List<Business> findByReputationScoreGreaterThanEqual(Double minScore);

    /**
     * Find businesses by badge type
     * Useful for finding "Top Rated" or "Rising Star" businesses
     */
    List<Business> findByBadge(String badge);

    /**
     * Find businesses in a specific city/location
     * Useful if you want location-based searching
     */
    List<Business> findByAddressContainingIgnoreCase(String location);
}