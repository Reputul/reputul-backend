package com.reputul.backend.repositories;

import com.reputul.backend.models.Organization;
import com.reputul.backend.models.Usage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for usage tracking and analytics
 * Critical for billing integration and usage reporting
 */
@Repository
public interface UsageRepository extends JpaRepository<Usage, Long> {

    // ========== BASIC QUERIES ==========

    /**
     * Find all usage for an organization
     */
    Page<Usage> findByOrganizationOrderByCreatedAtDesc(Organization organization, Pageable pageable);

    /**
     * Find usage by organization and metric type
     */
    List<Usage> findByOrganizationAndMetric(Organization organization, Usage.Metric metric);

    /**
     * Find usage by organization within a date range
     */
    @Query("SELECT u FROM Usage u WHERE u.organization = :org AND u.createdAt BETWEEN :startDate AND :endDate ORDER BY u.createdAt DESC")
    List<Usage> findByOrganizationAndDateRange(
            @Param("org") Organization organization,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    // ========== BILLING QUERIES ==========

    /**
     * Find unbilled usage for an organization
     */
    @Query("SELECT u FROM Usage u WHERE u.organization = :org AND u.billed = false AND u.metric IN :metrics")
    List<Usage> findUnbilledUsage(
            @Param("org") Organization organization,
            @Param("metrics") List<Usage.Metric> metrics
    );

    /**
     * Find usage for current billing period
     */
    @Query("SELECT u FROM Usage u WHERE u.organization = :org AND u.periodStart >= :periodStart AND u.periodEnd <= :periodEnd")
    List<Usage> findByOrganizationAndBillingPeriod(
            @Param("org") Organization organization,
            @Param("periodStart") OffsetDateTime periodStart,
            @Param("periodEnd") OffsetDateTime periodEnd
    );

    /**
     * Mark usage as billed
     */
    @Query("UPDATE Usage u SET u.billed = true, u.stripeUsageRecordId = :stripeId WHERE u.id IN :usageIds")
    void markAsBilled(@Param("usageIds") List<Long> usageIds, @Param("stripeId") String stripeId);

    // ========== ANALYTICS QUERIES ==========

    /**
     * Count total usage by metric for an organization in a period
     */
    @Query("SELECT SUM(u.quantity) FROM Usage u WHERE u.organization = :org AND u.metric = :metric AND u.createdAt BETWEEN :startDate AND :endDate")
    Integer sumUsageByMetricAndPeriod(
            @Param("org") Organization organization,
            @Param("metric") Usage.Metric metric,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Count total SMS sent by organization in current month
     */
    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM Usage u WHERE u.organization = :org AND u.metric = 'SMS_SENT' AND u.createdAt >= :startOfMonth")
    Integer countSmsUsageThisMonth(
            @Param("org") Organization organization,
            @Param("startOfMonth") OffsetDateTime startOfMonth
    );

    /**
     * Count total emails sent by organization in current month
     */
    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM Usage u WHERE u.organization = :org AND u.metric = 'EMAIL_SENT' AND u.createdAt >= :startOfMonth")
    Integer countEmailUsageThisMonth(
            @Param("org") Organization organization,
            @Param("startOfMonth") OffsetDateTime startOfMonth
    );

    /**
     * Get usage statistics grouped by metric
     */
    @Query("SELECT u.metric, SUM(u.quantity) as total, SUM(u.costCents) as totalCost " +
            "FROM Usage u WHERE u.organization = :org AND u.createdAt BETWEEN :startDate AND :endDate " +
            "GROUP BY u.metric")
    List<Object[]> getUsageStatsByMetric(
            @Param("org") Organization organization,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Calculate total cost for unbilled usage
     */
    @Query("SELECT COALESCE(SUM(u.costCents), 0) FROM Usage u WHERE u.organization = :org AND u.billed = false AND u.costCents > 0")
    Integer calculateUnbilledCost(@Param("org") Organization organization);

    // ========== REFERENCE QUERIES ==========

    /**
     * Find usage by reference
     */
    Optional<Usage> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);

    /**
     * Find all usage for a specific reference
     */
    List<Usage> findAllByReferenceTypeAndReferenceId(String referenceType, Long referenceId);

    // ========== CLEANUP QUERIES ==========

    /**
     * Delete old usage records (for data retention policies)
     */
    @Query("DELETE FROM Usage u WHERE u.createdAt < :cutoffDate AND u.billed = true")
    void deleteOldBilledUsage(@Param("cutoffDate") OffsetDateTime cutoffDate);

    /**
     * Find duplicate usage records (for cleanup)
     */
    @Query("SELECT u FROM Usage u WHERE u.organization = :org AND u.metric = :metric " +
            "AND u.referenceType = :refType AND u.referenceId = :refId " +
            "ORDER BY u.createdAt DESC")
    List<Usage> findDuplicates(
            @Param("org") Organization organization,
            @Param("metric") Usage.Metric metric,
            @Param("refType") String referenceType,
            @Param("refId") Long referenceId
    );

    // ========== REPORTING QUERIES ==========

    /**
     * Daily usage report
     */
    @Query(value = "SELECT DATE(u.created_at) as usage_date, u.metric, SUM(u.quantity) as total_quantity, SUM(u.cost_cents) as total_cost " +
            "FROM usage_tracking u " +
            "WHERE u.organization_id = :orgId AND u.created_at BETWEEN :startDate AND :endDate " +
            "GROUP BY DATE(u.created_at), u.metric " +
            "ORDER BY usage_date DESC, u.metric",
            nativeQuery = true)
    List<Object[]> generateDailyUsageReport(
            @Param("orgId") Long organizationId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate
    );

    /**
     * Check if organization has exceeded plan limits
     */
    @Query("SELECT CASE WHEN COUNT(u) > :limit THEN true ELSE false END " +
            "FROM Usage u WHERE u.organization = :org AND u.metric = :metric " +
            "AND u.createdAt >= :periodStart")
    boolean hasExceededLimit(
            @Param("org") Organization organization,
            @Param("metric") Usage.Metric metric,
            @Param("limit") Integer limit,
            @Param("periodStart") OffsetDateTime periodStart
    );
}