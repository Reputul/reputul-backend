// ReviewRequestRepository.java
package com.reputul.backend.repositories;

import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    List<ReviewRequest> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    List<ReviewRequest> findByBusinessIdAndStatusOrderByCreatedAtDesc(Long businessId, ReviewRequest.RequestStatus status);

    @Query("SELECT rr FROM ReviewRequest rr WHERE rr.business.id = :businessId AND rr.business.user.id = :ownerId ORDER BY rr.createdAt DESC")
    List<ReviewRequest> findByBusinessIdAndOwnerId(@Param("businessId") Long businessId, @Param("ownerId") Long ownerId);

    @Query("SELECT rr FROM ReviewRequest rr WHERE rr.business.user.id = :ownerId ORDER BY rr.createdAt DESC")
    List<ReviewRequest> findByOwnerId(@Param("ownerId") Long ownerId);

    long countByBusinessIdAndStatus(Long businessId, ReviewRequest.RequestStatus status);

    long countByBusinessIdAndCreatedAtAfter(Long businessId, LocalDateTime after);

    @Query("SELECT COUNT(rr) FROM ReviewRequest rr WHERE rr.business.user.id = :ownerId AND rr.createdAt >= :startDate")
    long countByOwnerIdAndCreatedAtAfter(@Param("ownerId") Long ownerId, @Param("startDate") LocalDateTime startDate);
}
