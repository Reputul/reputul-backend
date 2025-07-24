package com.reputul.backend.repositories;

import com.reputul.backend.models.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // Get all reviews for a business
    List<Review> findByBusinessId(Long businessId);

    // Get the most recent review for a business
    List<Review> findTop1ByBusinessIdOrderByCreatedAtDesc(Long businessId);

    // Get the average rating for a business
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.id = :businessId")
    Double findAverageRatingByBusinessId(@Param("businessId") Long businessId);

    // Count total reviews for a business
    int countByBusinessId(Long businessId);
}
