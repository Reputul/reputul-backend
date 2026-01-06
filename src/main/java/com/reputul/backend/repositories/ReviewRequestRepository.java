package com.reputul.backend.repositories;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Fetch review requests with all related entities to prevent LazyInitializationException
     */
    @Query("SELECT DISTINCT rr FROM ReviewRequest rr " +
            "LEFT JOIN FETCH rr.customer c " +
            "LEFT JOIN FETCH rr.business b " +
            "LEFT JOIN FETCH rr.emailTemplate et " +
            "WHERE b.user.id = :ownerId " +
            "ORDER BY rr.createdAt DESC")
    List<ReviewRequest> findByOwnerIdWithRelations(@Param("ownerId") Long ownerId);

    /**
     * Fetch review requests by business with all relations
     */
    @Query("SELECT DISTINCT rr FROM ReviewRequest rr " +
            "LEFT JOIN FETCH rr.customer c " +
            "LEFT JOIN FETCH rr.business b " +
            "LEFT JOIN FETCH rr.emailTemplate et " +
            "WHERE rr.business.id = :businessId " +
            "ORDER BY rr.createdAt DESC")
    List<ReviewRequest> findByBusinessIdWithRelations(@Param("businessId") Long businessId);








    // ========== SMS-SPECIFIC METHODS ==========

    /**
     * Find review request by Twilio SMS message ID
     */
    Optional<ReviewRequest> findBySmsMessageId(String smsMessageId);

    /**
     * Find all SMS review requests
     */
    @Query(value = "SELECT * FROM review_requests WHERE delivery_method = 'SMS' ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findAllSmsRequests();

    /**
     * Find SMS requests by user
     */
    @Query(value = """
        SELECT r.* FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'SMS'
        ORDER BY r.created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> findSmsRequestsByUserId(@Param("userId") Long userId);

    /**
     * Find SMS requests by business
     */
    @Query(value = "SELECT * FROM review_requests WHERE business_id = :businessId AND delivery_method = 'SMS' ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findSmsRequestsByBusinessId(@Param("businessId") Long businessId);

    /**
     * Find SMS requests by phone number
     */
    List<ReviewRequest> findByRecipientPhoneOrderByCreatedAtDesc(String recipientPhone);

    /**
     * Find SMS requests by status
     */
    @Query(value = "SELECT * FROM review_requests WHERE delivery_method = 'SMS' AND status = :status ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findSmsRequestsByStatus(@Param("status") String status);

    /**
     * Count SMS requests by user and date range
     */
    @Query(value = """
        SELECT COUNT(*) FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'SMS'
        AND r.created_at BETWEEN :startDate AND :endDate
        """, nativeQuery = true)
    Long countSmsRequestsByUserAndDateRange(@Param("userId") Long userId,
                                            @Param("startDate") OffsetDateTime startDate,
                                            @Param("endDate") OffsetDateTime endDate);

    /**
     * Count delivery method usage by user
     */
    @Query(value = """
        SELECT r.delivery_method, COUNT(*) FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId
        GROUP BY r.delivery_method
        """, nativeQuery = true)
    List<Object[]> countDeliveryMethodsByUserId(@Param("userId") Long userId);

    /**
     * Find failed SMS requests for retry
     */
    @Query(value = """
        SELECT * FROM review_requests
        WHERE delivery_method = 'SMS' AND status = 'FAILED' AND created_at > :since
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> findFailedSmsRequestsSince(@Param("since") OffsetDateTime since);

    /**
     * Find pending SMS requests (for cleanup/monitoring)
     */
    @Query(value = "SELECT * FROM review_requests WHERE delivery_method = 'SMS' AND status = 'PENDING' AND created_at < :olderThan", nativeQuery = true)
    List<ReviewRequest> findPendingSmsRequestsOlderThan(@Param("olderThan") OffsetDateTime olderThan);

    /**
     * Find requests by SMS status (Twilio status)
     */
    @Query(value = "SELECT * FROM review_requests WHERE sms_status = :smsStatus ORDER BY updated_at DESC", nativeQuery = true)
    List<ReviewRequest> findBySmsStatus(@Param("smsStatus") String smsStatus);

    /**
     * Get SMS analytics for user
     * FIXED: Using native SQL for CASE WHEN with string enum values
     */
    @Query(value = """
        SELECT 
            COUNT(*) as total,
            COUNT(CASE WHEN r.status = 'SENT' THEN 1 END) as sent,
            COUNT(CASE WHEN r.status = 'DELIVERED' THEN 1 END) as delivered,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed
        FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'SMS'
        """, nativeQuery = true)
    Object[] getSmsAnalyticsByUserId(@Param("userId") Long userId);

    /**
     * Get recent SMS activity for dashboard
     */
    @Query(value = """
        SELECT r.* FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'SMS' AND r.created_at > :since
        ORDER BY r.created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> getRecentSmsActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    // ========== EMAIL-SPECIFIC METHODS ==========

    /**
     * Find review request by SendGrid/Resend message ID
     * Used by webhook handler to update email status
     */
    Optional<ReviewRequest> findBySendgridMessageId(String sendgridMessageId);

    /**
     * Find the most recent pending review request for a customer
     * Used when sending emails to attach the message ID
     */
    Optional<ReviewRequest> findTopByCustomerAndStatusOrderByCreatedAtDesc(
            Customer customer,
            ReviewRequest.RequestStatus status
    );

    /**
     * Find all email review requests
     */
    @Query(value = "SELECT * FROM review_requests WHERE delivery_method = 'EMAIL' ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findAllEmailRequests();

    /**
     * Find email requests by user
     */
    @Query(value = """
        SELECT r.* FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'EMAIL'
        ORDER BY r.created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> findEmailRequestsByUserId(@Param("userId") Long userId);

    /**
     * Find email requests by business
     */
    @Query(value = "SELECT * FROM review_requests WHERE business_id = :businessId AND delivery_method = 'EMAIL' ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findEmailRequestsByBusinessId(@Param("businessId") Long businessId);

    /**
     * Find email requests with specific status
     */
    @Query(value = "SELECT * FROM review_requests WHERE email_status = :emailStatus ORDER BY created_at DESC", nativeQuery = true)
    List<ReviewRequest> findByEmailStatus(@Param("emailStatus") String emailStatus);

    /**
     * Count email open rate for a business
     */
    @Query(value = """
        SELECT COUNT(CASE WHEN email_status IN ('opened', 'clicked', 'completed') THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0)
        FROM review_requests
        WHERE business_id = :businessId AND delivery_method = 'EMAIL' AND status = 'SENT'
        """, nativeQuery = true)
    Double calculateEmailOpenRate(@Param("businessId") Long businessId);

    /**
     * Count email click rate for a business
     */
    @Query(value = """
        SELECT COUNT(CASE WHEN email_status IN ('clicked', 'completed') THEN 1 END) * 100.0 /
            NULLIF(COUNT(CASE WHEN email_status IN ('opened', 'clicked', 'completed') THEN 1 END), 0)
        FROM review_requests
        WHERE business_id = :businessId AND delivery_method = 'EMAIL' AND email_status IS NOT NULL
        """, nativeQuery = true)
    Double calculateEmailClickRate(@Param("businessId") Long businessId);

    /**
     * Find bounced emails for a business
     */
    @Query(value = """
        SELECT * FROM review_requests
        WHERE business_id = :businessId AND email_status IN ('bounce', 'dropped', 'spamreport')
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> findBouncedEmails(@Param("businessId") Long businessId);

    /**
     * Get email delivery stats for a time period
     */
    @Query(value = """
        SELECT 
            COUNT(*) as total,
            COUNT(CASE WHEN email_status = 'delivered' THEN 1 END) as delivered,
            COUNT(CASE WHEN email_status = 'opened' THEN 1 END) as opened,
            COUNT(CASE WHEN email_status = 'clicked' THEN 1 END) as clicked,
            COUNT(CASE WHEN email_status IN ('bounce', 'dropped') THEN 1 END) as failed
        FROM review_requests
        WHERE business_id = :businessId AND delivery_method = 'EMAIL'
        AND created_at BETWEEN :startDate AND :endDate
        """, nativeQuery = true)
    List<Object[]> getEmailStats(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find review requests with delivery issues
     */
    @Query(value = """
        SELECT * FROM review_requests
        WHERE delivery_method = 'EMAIL' AND status = 'SENT' AND email_status IS NULL AND sent_at < :cutoffTime
        """, nativeQuery = true)
    List<ReviewRequest> findStuckEmailRequests(@Param("cutoffTime") OffsetDateTime cutoffTime);

    /**
     * Count email requests by user and date range
     */
    @Query(value = """
        SELECT COUNT(*) FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'EMAIL'
        AND r.created_at BETWEEN :startDate AND :endDate
        """, nativeQuery = true)
    Long countEmailRequestsByUserAndDateRange(
            @Param("userId") Long userId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find failed email requests for retry
     */
    @Query(value = """
        SELECT * FROM review_requests
        WHERE delivery_method = 'EMAIL' AND status = 'FAILED' AND created_at > :since
        ORDER BY created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> findFailedEmailRequestsSince(@Param("since") OffsetDateTime since);

    /**
     * Get email analytics for user
     * FIXED: Using native SQL for CASE WHEN with string enum values
     */
    @Query(value = """
        SELECT 
            COUNT(*) as total,
            COUNT(CASE WHEN r.status = 'SENT' THEN 1 END) as sent,
            COUNT(CASE WHEN r.status = 'DELIVERED' THEN 1 END) as delivered,
            COUNT(CASE WHEN r.email_status = 'opened' THEN 1 END) as opened,
            COUNT(CASE WHEN r.email_status = 'clicked' THEN 1 END) as clicked,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed
        FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'EMAIL'
        """, nativeQuery = true)
    Object[] getEmailAnalyticsByUserId(@Param("userId") Long userId);

    /**
     * Get recent email activity for dashboard
     */
    @Query(value = """
        SELECT r.* FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.delivery_method = 'EMAIL' AND r.created_at > :since
        ORDER BY r.created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> getRecentEmailActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    // ========== COMBINED ANALYTICS (EMAIL + SMS) ==========

    /**
     * Get overall delivery stats for all methods
     */
    @Query(value = """
        SELECT 
            r.delivery_method,
            COUNT(*) as total,
            COUNT(CASE WHEN r.status IN ('SENT', 'DELIVERED', 'OPENED', 'CLICKED', 'COMPLETED') THEN 1 END) as successful,
            COUNT(CASE WHEN r.status = 'FAILED' THEN 1 END) as failed,
            COUNT(CASE WHEN r.status = 'COMPLETED' THEN 1 END) as completed,
            AVG(CASE WHEN r.status = 'COMPLETED' AND r.reviewed_at IS NOT NULL AND r.sent_at IS NOT NULL THEN 
                EXTRACT(EPOCH FROM (r.reviewed_at - r.sent_at)) / 3600
            END) as avg_hours_to_complete
        FROM review_requests r
        WHERE r.business_id = :businessId
        AND r.created_at BETWEEN :startDate AND :endDate
        GROUP BY r.delivery_method
        """, nativeQuery = true)
    List<Object[]> getDeliveryMethodStats(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Get combined recent activity
     */
    @Query(value = """
        SELECT r.* FROM review_requests r
        JOIN businesses b ON r.business_id = b.id
        WHERE b.user_id = :userId AND r.created_at > :since
        ORDER BY r.created_at DESC
        """, nativeQuery = true)
    List<ReviewRequest> getRecentActivity(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

    /**
     * Count total review requests by business
     */
    @Query("SELECT COUNT(r) FROM ReviewRequest r WHERE r.business.id = :businessId")
    Long countByBusinessId(@Param("businessId") Long businessId);

    /**
     * Get completion rate by delivery method
     */
    @Query(value = """
        SELECT 
            delivery_method,
            COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0) as completion_rate
        FROM review_requests
        WHERE business_id = :businessId AND sent_at IS NOT NULL
        GROUP BY delivery_method
        """, nativeQuery = true)
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
     * FIXED: Using native SQL for IN clause with string enum values
     */
    @Query(value = """
        SELECT COUNT(*) > 0
        FROM review_requests
        WHERE customer_id = :customerId
        AND status IN ('PENDING', 'SENT', 'DELIVERED', 'OPENED')
        """, nativeQuery = true)
    boolean hasPendingReviewRequest(@Param("customerId") Long customerId);

    /**
     * Get review request performance metrics
     */
    @Query(value = """
        SELECT 
            DATE(created_at) as date,
            COUNT(*) as total_sent,
            COUNT(CASE WHEN status = 'COMPLETED' THEN 1 END) as completed,
            COUNT(CASE WHEN delivery_method = 'EMAIL' THEN 1 END) as emails_sent,
            COUNT(CASE WHEN delivery_method = 'SMS' THEN 1 END) as sms_sent
        FROM review_requests
        WHERE business_id = :businessId AND created_at >= :startDate
        GROUP BY DATE(created_at)
        ORDER BY date DESC
        """, nativeQuery = true)
    List<Object[]> getDailyMetrics(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate
    );

    /**
     * Clean up old pending requests
     */
    @Modifying
    @Query(value = "DELETE FROM review_requests WHERE status = 'PENDING' AND created_at < :cutoffDate", nativeQuery = true)
    void deleteOldPendingRequests(@Param("cutoffDate") OffsetDateTime cutoffDate);

    /**
     * Find the most recent review request sent to an email address for a business
     * within a specific time window (used for 30-day frequency limiting)
     *
     * NOTE: Uses recipientEmail field (not email field)
     *
     * @param businessId The business ID
     * @param recipientEmail The customer email
     * @param sentAfter Only include requests sent after this timestamp
     * @return The most recent review request if found
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.recipientEmail = :recipientEmail " +
            "AND r.sentAt IS NOT NULL " +
            "AND r.sentAt >= :sentAfter " +
            "ORDER BY r.sentAt DESC")
    Optional<ReviewRequest> findFirstByBusinessIdAndRecipientEmailAndSentAtAfterOrderBySentAtDesc(
            @Param("businessId") Long businessId,
            @Param("recipientEmail") String recipientEmail,
            @Param("sentAfter") OffsetDateTime sentAfter
    );

    /**
     * Find the most recent review request sent to a phone number for a business
     * within a specific time window (used for 30-day frequency limiting)
     *
     * NOTE: Uses recipientPhone field (not phone field)
     *
     * @param businessId The business ID
     * @param recipientPhone The customer phone number
     * @param sentAfter Only include requests sent after this timestamp
     * @return The most recent review request if found
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.recipientPhone = :recipientPhone " +
            "AND r.sentAt IS NOT NULL " +
            "AND r.sentAt >= :sentAfter " +
            "ORDER BY r.sentAt DESC")
    Optional<ReviewRequest> findFirstByBusinessIdAndRecipientPhoneAndSentAtAfterOrderBySentAtDesc(
            @Param("businessId") Long businessId,
            @Param("recipientPhone") String recipientPhone,
            @Param("sentAfter") OffsetDateTime sentAfter
    );

    /**
     * Find the most recent review request CREATED for an email address for a business
     * within a specific time window (used for 30-day frequency limiting)
     *
     * NOTE: Uses createdAt instead of sentAt to catch PENDING requests too
     *
     * @param businessId The business ID
     * @param recipientEmail The customer email
     * @param createdAfter Only include requests created after this timestamp
     * @return The most recent review request if found
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.recipientEmail = :recipientEmail " +
            "AND r.createdAt >= :createdAfter " +
            "ORDER BY r.createdAt DESC")
    Optional<ReviewRequest> findFirstByBusinessIdAndRecipientEmailAndCreatedAtAfterOrderByCreatedAtDesc(
            @Param("businessId") Long businessId,
            @Param("recipientEmail") String recipientEmail,
            @Param("createdAfter") OffsetDateTime createdAfter
    );

    /**
     * Find the most recent review request CREATED for a phone number for a business
     * within a specific time window (used for 30-day frequency limiting)
     *
     * NOTE: Uses createdAt instead of sentAt to catch PENDING requests too
     *
     * @param businessId The business ID
     * @param recipientPhone The customer phone number
     * @param createdAfter Only include requests created after this timestamp
     * @return The most recent review request if found
     */
    @Query("SELECT r FROM ReviewRequest r WHERE r.business.id = :businessId " +
            "AND r.recipientPhone = :recipientPhone " +
            "AND r.createdAt >= :createdAfter " +
            "ORDER BY r.createdAt DESC")
    Optional<ReviewRequest> findFirstByBusinessIdAndRecipientPhoneAndCreatedAtAfterOrderByCreatedAtDesc(
            @Param("businessId") Long businessId,
            @Param("recipientPhone") String recipientPhone,
            @Param("createdAfter") OffsetDateTime createdAfter
    );
}