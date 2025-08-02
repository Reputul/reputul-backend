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

    // Check if business exists and belongs to user
    boolean existsByIdAndUser(Long id, User user);

    // Find businesses by platform configuration status
    List<Business> findByUserAndReviewPlatformsConfigured(User user, Boolean reviewPlatformsConfigured);

    // Count businesses by platform configuration status
    long countByUserAndReviewPlatformsConfigured(User user, Boolean reviewPlatformsConfigured);

    // Find all businesses for owner ordered by creation date
    List<Business> findByUserOrderByCreatedAtDesc(User user);
}