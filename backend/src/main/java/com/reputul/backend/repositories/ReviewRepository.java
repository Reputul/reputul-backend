package com.reputul.backend.repositories;

import com.reputul.backend.models.Review;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // Get all reviews for a business
    List<Review> findByBusinessId(Long businessId);

    // Get all reviews for a business with sorting
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId")
    List<Review> findByBusinessIdOrderBy(@Param("businessId") Long businessId, Sort sort);

    // Get the most recent review for a business
    List<Review> findTop1ByBusinessIdOrderByCreatedAtDesc(Long businessId);

    // Get the average rating for a business
    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.business.id = :businessId")
    Double findAverageRatingByBusinessId(@Param("businessId") Long businessId);

    // Count total reviews for a business
    int countByBusinessId(Long businessId);

    // Get reviews by rating range for a business
    @Query("SELECT r FROM Review r WHERE r.business.id = :businessId AND r.rating >= :minRating AND r.rating <= :maxRating ORDER BY r.createdAt DESC")
    List<Review> findByBusinessIdAndRatingBetween(
            @Param("businessId") Long businessId,
            @Param("minRating") int minRating,
            @Param("maxRating") int maxRating
    );

    // Get recent reviews across all businesses (for dashboard)
    @Query("SELECT r FROM Review r ORDER BY r.createdAt DESC")
    List<Review> findTop10ByOrderByCreatedAtDesc();

    // Get reviews by source (manual, public, etc.)
    List<Review> findByBusinessIdAndSource(Long businessId, String source);
}