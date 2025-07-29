package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    List<Business> findByOwnerId(Long ownerId);
    Optional<Business> findByIdAndOwnerId(Long id, Long ownerId);

    // Find business by ID and owner (security check)
    Optional<Business> findByIdAndOwner(Long id, User owner);

    // Find all businesses for a specific owner
    List<Business> findByOwner(User owner);

    // Check if business exists and belongs to user
    boolean existsByIdAndOwner(Long id, User owner);

    // Find businesses by platform configuration status
    List<Business> findByOwnerAndReviewPlatformsConfigured(User owner, Boolean reviewPlatformsConfigured);

    // Count businesses by platform configuration status
    long countByOwnerAndReviewPlatformsConfigured(User owner, Boolean reviewPlatformsConfigured);

    // Find all businesses for owner ordered by creation date
    List<Business> findByOwnerOrderByCreatedAtDesc(User owner);
}