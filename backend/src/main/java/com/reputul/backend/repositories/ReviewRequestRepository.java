package com.reputul.backend.repositories;

import com.reputul.backend.models.ReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    // Existing methods (keep all your current methods)
    List<ReviewRequest> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId ORDER BY r.createdAt DESC")
    List<ReviewRequest> findByOwnerId(@Param("userId") Long userId);

    // NEW: SMS-specific methods

    /**
     * Find review request by Twilio SMS message ID
     */
    Optional<ReviewRequest> findBySmsMessageId(String smsMessageId);

    /**
     * Find all SMS review requests
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findAllSmsRequests();

    /**
     * Find SMS requests by user
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId AND r.deliveryMethod = 'SMS' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findSmsRequestsByUserId(@Param("userId") Long userId);

    /**
     * Find SMS requests by business
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId AND r.deliveryMethod = 'SMS' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findSmsRequestsByBusinessId(@Param("businessId") Long businessId);

    /**
     * Find SMS requests by phone number
     */
    List<ReviewRequest> findByRecipientPhoneOrderByCreatedAtDesc(String recipientPhone);

    /**
     * Find SMS requests by status
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' AND r.status = :status ORDER BY r.createdAt DESC")
    List<ReviewRequest> findSmsRequestsByStatus(@Param("status") ReviewRequest.RequestStatus status);

    /**
     * Count SMS requests by user and date range
     */
    @Query("SELECT COUNT(r) FROM ReviewRequest r WHERE r.business.user.id = :userId AND r.deliveryMethod = 'SMS' AND r.createdAt BETWEEN :startDate AND :endDate")
    Long countSmsRequestsByUserAndDateRange(@Param("userId") Long userId,
                                            @Param("startDate") LocalDateTime startDate,
                                            @Param("endDate") LocalDateTime endDate);

    /**
     * Count delivery method usage by user
     */
    @Query("SELECT r.deliveryMethod, COUNT(r) FROM ReviewRequest r WHERE r.business.user.id = :userId GROUP BY r.deliveryMethod")
    List<Object[]> countDeliveryMethodsByUserId(@Param("userId") Long userId);

    /**
     * Find failed SMS requests for retry
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' AND r.status = 'FAILED' AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> findFailedSmsRequestsSince(@Param("since") LocalDateTime since);

    /**
     * Find pending SMS requests (for cleanup/monitoring)
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' AND r.status = 'PENDING' AND r.createdAt < :olderThan")
    List<ReviewRequest> findPendingSmsRequestsOlderThan(@Param("olderThan") LocalDateTime olderThan);

    /**
     * Find requests by SMS status (Twilio status)
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.smsStatus = :smsStatus ORDER BY r.updatedAt DESC")
    List<ReviewRequest> findBySmsStatus(@Param("smsStatus") String smsStatus);

    /**
     * Get SMS analytics for user
     */
    @Query("""
        SELECT 
            COUNT(r) as total,
            COUNT(CASE WHEN r.status = 'SENT' THEN 1 END) as sent,
            COUNT(CASE WHEN r.status = 'DELIVERED' THEN 1 END) as delivered,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed
        FROM ReviewRequest r 
        WHERE r.business.user.id = :userId AND r.deliveryMethod = 'SMS'
        """)
    Object[] getSmsAnalyticsByUserId(@Param("userId") Long userId);

    /**
     * Get recent SMS activity for dashboard
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId AND r.deliveryMethod = 'SMS' AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> getRecentSmsActivity(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}