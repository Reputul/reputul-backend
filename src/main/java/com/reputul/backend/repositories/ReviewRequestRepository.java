package com.reputul.backend.repositories;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRequestRepository extends JpaRepository<ReviewRequest, Long> {

    // ========== EXISTING BASIC METHODS ==========

    List<ReviewRequest> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId ORDER BY r.createdAt DESC")
    List<ReviewRequest> findByOwnerId(@Param("userId") Long userId);

    // ========== SMS-SPECIFIC METHODS (EXISTING) ==========

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
                                            @Param("startDate") OffsetDateTime startDate,
                                            @Param("endDate") OffsetDateTime endDate);

    /**
     * Count delivery method usage by user
     */
    @Query("SELECT r.deliveryMethod, COUNT(r) FROM ReviewRequest r WHERE r.business.user.id = :userId GROUP BY r.deliveryMethod")
    List<Object[]> countDeliveryMethodsByUserId(@Param("userId") Long userId);

    /**
     * Find failed SMS requests for retry
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' AND r.status = 'FAILED' AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> findFailedSmsRequestsSince(@Param("since") OffsetDateTime since);

    /**
     * Find pending SMS requests (for cleanup/monitoring)
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'SMS' AND r.status = 'PENDING' AND r.createdAt < :olderThan")
    List<ReviewRequest> findPendingSmsRequestsOlderThan(@Param("olderThan") OffsetDateTime olderThan);

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
    List<ReviewRequest> getRecentSmsActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    // ========== EMAIL-SPECIFIC METHODS (NEW) ==========

    /**
     * Find review request by SendGrid message ID
     * Used by webhook handler to update email status
     */
    Optional<ReviewRequest> findBySendgridMessageId(String sendgridMessageId);

    /**
     * Find the most recent pending review request for a customer
     * Used when sending emails to attach the SendGrid message ID
     */
    Optional<ReviewRequest> findTopByCustomerAndStatusOrderByCreatedAtDesc(
            Customer customer,
            ReviewRequest.RequestStatus status
    );

    /**
     * Find all email review requests
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'EMAIL' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findAllEmailRequests();

    /**
     * Find email requests by user
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId " +
            "AND r.deliveryMethod = 'EMAIL' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findEmailRequestsByUserId(@Param("userId") Long userId);

    /**
     * Find email requests by business
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.deliveryMethod = 'EMAIL' ORDER BY r.createdAt DESC")
    List<ReviewRequest> findEmailRequestsByBusinessId(@Param("businessId") Long businessId);

    /**
     * Find email requests with specific status
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.emailStatus = :status " +
            "ORDER BY r.createdAt DESC")
    List<ReviewRequest> findByEmailStatus(@Param("status") String emailStatus);

    /**
     * Count email open rate for a business
     */
    @Query("SELECT COUNT(CASE WHEN r.emailStatus IN ('opened', 'clicked', 'completed') THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0) " +
            "FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.deliveryMethod = 'EMAIL' AND r.status = 'SENT'")
    Double calculateEmailOpenRate(@Param("businessId") Long businessId);

    /**
     * Count email click rate for a business
     */
    @Query("SELECT COUNT(CASE WHEN r.emailStatus IN ('clicked', 'completed') THEN 1 END) * 100.0 / " +
            "NULLIF(COUNT(CASE WHEN r.emailStatus IN ('opened', 'clicked', 'completed') THEN 1 END), 0) " +
            "FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.deliveryMethod = 'EMAIL' AND r.emailStatus IS NOT NULL")
    Double calculateEmailClickRate(@Param("businessId") Long businessId);

    /**
     * Find bounced emails for a business
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.emailStatus IN ('bounce', 'dropped', 'spamreport') " +
            "ORDER BY r.createdAt DESC")
    List<ReviewRequest> findBouncedEmails(@Param("businessId") Long businessId);

    /**
     * Get email delivery stats for a time period
     */
    @Query("""
        SELECT 
            COUNT(*) as total,
            COUNT(CASE WHEN r.emailStatus = 'delivered' THEN 1 END) as delivered,
            COUNT(CASE WHEN r.emailStatus = 'opened' THEN 1 END) as opened,
            COUNT(CASE WHEN r.emailStatus = 'clicked' THEN 1 END) as clicked,
            COUNT(CASE WHEN r.emailStatus IN ('bounce', 'dropped') THEN 1 END) as failed
        FROM ReviewRequest r 
        WHERE r.business.id = :businessId 
        AND r.deliveryMethod = 'EMAIL' 
        AND r.createdAt BETWEEN :startDate AND :endDate
        """)
    List<Object[]> getEmailStats(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find review requests with delivery issues
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'EMAIL' " +
            "AND r.status = 'SENT' AND r.emailStatus IS NULL " +
            "AND r.sentAt < :cutoffTime")
    List<ReviewRequest> findStuckEmailRequests(@Param("cutoffTime") OffsetDateTime cutoffTime);

    /**
     * Count email requests by user and date range
     */
    @Query("SELECT COUNT(r) FROM ReviewRequest r WHERE r.business.user.id = :userId " +
            "AND r.deliveryMethod = 'EMAIL' AND r.createdAt BETWEEN :startDate AND :endDate")
    Long countEmailRequestsByUserAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find failed email requests for retry
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.deliveryMethod = 'EMAIL' " +
            "AND r.status = 'FAILED' AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> findFailedEmailRequestsSince(@Param("since") OffsetDateTime since);

    /**
     * Get email analytics for user
     */
    @Query("""
        SELECT 
            COUNT(r) as total,
            COUNT(CASE WHEN r.status = 'SENT' THEN 1 END) as sent,
            COUNT(CASE WHEN r.status = 'DELIVERED' THEN 1 END) as delivered,
            COUNT(CASE WHEN r.emailStatus = 'opened' THEN 1 END) as opened,
            COUNT(CASE WHEN r.emailStatus = 'clicked' THEN 1 END) as clicked,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed
        FROM ReviewRequest r 
        WHERE r.business.user.id = :userId AND r.deliveryMethod = 'EMAIL'
        """)
    Object[] getEmailAnalyticsByUserId(@Param("userId") Long userId);

    /**
     * Get recent email activity for dashboard
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId " +
            "AND r.deliveryMethod = 'EMAIL' AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> getRecentEmailActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    // ========== COMBINED ANALYTICS (EMAIL + SMS) ==========

    /**
     * Get overall delivery stats for all methods
     * FIXED: Changed EXTRACT(EPOCH FROM ...) to use TIMESTAMPDIFF for Hibernate 6.5+ compatibility
     */
    @Query("""
        SELECT 
            r.deliveryMethod,
            COUNT(r) as total,
            COUNT(CASE WHEN r.status IN ('SENT', 'DELIVERED', 'OPENED', 'CLICKED', 'COMPLETED') THEN 1 END) as successful,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed,
            AVG(CASE WHEN r.status = 'COMPLETED' AND r.reviewedAt IS NOT NULL AND r.sentAt IS NOT NULL THEN 
                TIMESTAMPDIFF(HOUR, r.sentAt, r.reviewedAt)
            END) as avgHoursToComplete
        FROM ReviewRequest r 
        WHERE r.business.id = :businessId 
        AND r.createdAt BETWEEN :startDate AND :endDate
        GROUP BY r.deliveryMethod
        """)
    List<Object[]> getDeliveryMethodStats(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Get combined recent activity
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.user.id = :userId " +
            "AND r.createdAt > :since ORDER BY r.createdAt DESC")
    List<ReviewRequest> getRecentActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    /**
     * Count total review requests by business
     */
    @Query("SELECT COUNT(r) FROM ReviewRequest r WHERE r.business.id = :businessId")
    Long countByBusinessId(@Param("businessId") Long businessId);

    /**
     * Get completion rate by delivery method
     */
    @Query("""
        SELECT 
            r.deliveryMethod,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0) as completionRate
        FROM ReviewRequest r 
        WHERE r.business.id = :businessId 
        AND r.sentAt IS NOT NULL
        GROUP BY r.deliveryMethod
        """)
    List<Object[]> getCompletionRateByDeliveryMethod(@Param("businessId") Long businessId);

    /**
     * Find review requests by customer
     */
    List<ReviewRequest> findByCustomerOrderByCreatedAtDesc(Customer customer);

    /**
     * Find review requests by customer email
     */
    List<ReviewRequest> findByRecipientEmailOrderByCreatedAtDesc(String recipientEmail);

    /**
     * Check if customer has pending review request
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END " +
            "FROM ReviewRequest r WHERE r.customer = :customer " +
            "AND r.status IN ('PENDING', 'SENT', 'DELIVERED', 'OPENED')")
    boolean hasPendingReviewRequest(@Param("customer") Customer customer);

    /**
     * Get review request performance metrics
     */
    @Query("""
        SELECT 
            DATE(r.createdAt) as date,
            COUNT(r) as totalSent,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed,
            COUNT(CASE WHEN r.deliveryMethod = 'EMAIL' THEN 1 END) as emailsSent,
            COUNT(CASE WHEN r.deliveryMethod = 'SMS' THEN 1 END) as smsSent
        FROM ReviewRequest r 
        WHERE r.business.id = :businessId 
        AND r.createdAt >= :startDate
        GROUP BY DATE(r.createdAt)
        ORDER BY date DESC
        """)
    List<Object[]> getDailyMetrics(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate
    );

    /**
     * Clean up old pending requests
     */
    @Query("DELETE FROM ReviewRequest r WHERE r.status = 'PENDING' AND r.createdAt < :cutoffDate")
    void deleteOldPendingRequests(@Param("cutoffDate") OffsetDateTime cutoffDate);

    /**
     * Check if customer has any completed review requests
     */
    boolean existsByCustomerAndStatus(Customer customer, ReviewRequest.RequestStatus status);

    /**
     * Check if customer has any review requests since a specific date
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END " +
            "FROM ReviewRequest r WHERE r.customer.id = :customerId " +
            "AND r.createdAt >= :since")
    boolean hasRecentRequests(@Param("customerId") Long customerId,
                              @Param("since") OffsetDateTime since);
}