package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Repository for usage event tracking and analytics
 * Critical for billing integration and plan enforcement
 */
@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    /**
     * Count usage events by business, type, and date range
     * Used for billing period calculations
     */
    @Query("SELECT COUNT(u) FROM UsageEvent u WHERE u.business = :business AND u.type = :type AND u.createdAt BETWEEN :startDate AND :endDate")
    int countByBusinessAndTypeAndCreatedAtBetween(
            @Param("business") Business business,
            @Param("type") UsageEvent.UsageType type,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Sum quantity by business, type, and date range
     * For events that may have quantity > 1
     */
    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM UsageEvent u WHERE u.business = :business AND u.type = :type AND u.createdAt BETWEEN :startDate AND :endDate")
    int sumQuantityByBusinessAndTypeAndCreatedAtBetween(
            @Param("business") Business business,
            @Param("type") UsageEvent.UsageType type,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find all usage events for a business in date range
     */
    List<UsageEvent> findByBusinessAndCreatedAtBetween(Business business, OffsetDateTime startDate, OffsetDateTime endDate);

    /**
     * Find usage events by business and type
     */
    List<UsageEvent> findByBusinessAndType(Business business, UsageEvent.UsageType type);

    /**
     * Find usage events by business, type, and date range (with limit)
     */
    @Query("SELECT u FROM UsageEvent u WHERE u.business = :business AND u.type = :type AND u.createdAt BETWEEN :startDate AND :endDate ORDER BY u.createdAt DESC")
    List<UsageEvent> findByBusinessAndTypeAndCreatedAtBetweenOrderByCreatedAtDesc(
            @Param("business") Business business,
            @Param("type") UsageEvent.UsageType type,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Count billable events that were actually billed to Stripe
     */
    @Query("SELECT COUNT(u) FROM UsageEvent u WHERE u.business = :business AND u.overageBilled = true AND u.createdAt BETWEEN :startDate AND :endDate")
    int countBilledEventsInPeriod(
            @Param("business") Business business,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find events that should have been billed but weren't (for reconciliation)
     */
    @Query("SELECT u FROM UsageEvent u WHERE u.business = :business AND u.type IN :billableTypes AND u.overageBilled = false AND u.createdAt BETWEEN :startDate AND :endDate")
    List<UsageEvent> findUnbilledBillableEvents(
            @Param("business") Business business,
            @Param("billableTypes") List<UsageEvent.UsageType> billableTypes,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Find events by Stripe usage record ID (for webhook reconciliation)
     */
    UsageEvent findByStripeUsageRecordId(String stripeUsageRecordId);

    /**
     * Find events by reference ID (for deduplication)
     */
    List<UsageEvent> findByReferenceId(String referenceId);

    /**
     * Check if usage event already exists for reference ID and type
     * (prevents double-counting)
     */
    @Query("SELECT COUNT(u) > 0 FROM UsageEvent u WHERE u.business = :business AND u.type = :type AND u.referenceId = :referenceId")
    boolean existsByBusinessAndTypeAndReferenceId(
            @Param("business") Business business,
            @Param("type") UsageEvent.UsageType type,
            @Param("referenceId") String referenceId
    );

    /**
     * Get daily usage counts for a business in a date range (for analytics)
     */
    @Query("""
        SELECT DATE(u.createdAt) as date, u.type, COUNT(u) as count
        FROM UsageEvent u 
        WHERE u.business = :business AND u.createdAt BETWEEN :startDate AND :endDate 
        GROUP BY DATE(u.createdAt), u.type 
        ORDER BY date DESC
        """)
    List<Object[]> getDailyUsageCounts(
            @Param("business") Business business,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Get monthly usage summary for analytics
     */
    @Query("""
        SELECT 
            EXTRACT(YEAR FROM u.createdAt) as year,
            EXTRACT(MONTH FROM u.createdAt) as month,
            u.type,
            COUNT(u) as eventCount,
            SUM(u.quantity) as totalQuantity,
            COUNT(CASE WHEN u.overageBilled = true THEN 1 END) as billedCount
        FROM UsageEvent u 
        WHERE u.business = :business AND u.createdAt >= :startDate
        GROUP BY EXTRACT(YEAR FROM u.createdAt), EXTRACT(MONTH FROM u.createdAt), u.type
        ORDER BY year DESC, month DESC
        """)
    List<Object[]> getMonthlyUsageSummary(
            @Param("business") Business business,
            @Param("startDate") OffsetDateTime startDate
    );

    /**
     * Find recent usage events for a business (for debugging)
     */
    @Query("SELECT u FROM UsageEvent u WHERE u.business = :business ORDER BY u.createdAt DESC")
    List<UsageEvent> findRecentByBusiness(@Param("business") Business business);

    /**
     * Count total usage events for a business (all time)
     */
    long countByBusiness(Business business);

    /**
     * Delete old usage events (for data retention)
     * Generally keep usage events for billing reconciliation
     */
    @Query("DELETE FROM UsageEvent u WHERE u.createdAt < :cutoffDate AND u.overageBilled = false")
    void deleteOldUnbilledEvents(@Param("cutoffDate") OffsetDateTime cutoffDate);

    /**
     * Find usage events that need Stripe billing reconciliation
     */
    @Query("""
        SELECT u FROM UsageEvent u 
        WHERE u.type IN ('SMS_REVIEW_REQUEST_SENT', 'TWILIO_SMS_SENT') 
        AND u.overageBilled = true 
        AND u.stripeUsageRecordId IS NULL
        AND u.createdAt >= :sinceDate
        """)
    List<UsageEvent> findEventsNeedingStripeReconciliation(@Param("sinceDate") OffsetDateTime sinceDate);

    /**
     * Get usage statistics for plan enforcement
     */
    @Query("""
        SELECT 
            u.type,
            COUNT(u) as eventCount,
            SUM(u.quantity) as totalQuantity
        FROM UsageEvent u 
        WHERE u.business = :business 
        AND u.createdAt BETWEEN :startDate AND :endDate
        GROUP BY u.type
        """)
    List<Object[]> getUsageStatistics(
            @Param("business") Business business,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );
}