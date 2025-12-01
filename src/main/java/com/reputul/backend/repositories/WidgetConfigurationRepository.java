package com.reputul.backend.repositories;

import com.reputul.backend.models.WidgetConfiguration;
import com.reputul.backend.models.WidgetConfiguration.WidgetType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for WidgetConfiguration entities
 *
 * Provides data access methods for managing widget configurations
 * with proper organization-level tenant scoping.
 */
@Repository
public interface WidgetConfigurationRepository extends JpaRepository<WidgetConfiguration, Long> {

    // ================================================================
    // PUBLIC ACCESS QUERIES (No Auth Required)
    // ================================================================

    /**
     * Find widget by its public key (used for widget data API)
     */
    Optional<WidgetConfiguration> findByWidgetKey(String widgetKey);

    /**
     * Find active widget by key
     */
    Optional<WidgetConfiguration> findByWidgetKeyAndIsActiveTrue(String widgetKey);

    // ================================================================
    // ORGANIZATION-SCOPED QUERIES (Tenant Safety)
    // ================================================================

    /**
     * Find all widgets for an organization
     */
    List<WidgetConfiguration> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /**
     * Find widgets by organization and business
     */
    List<WidgetConfiguration> findByOrganizationIdAndBusinessIdOrderByCreatedAtDesc(
            Long organizationId, Long businessId);

    /**
     * Find widgets by organization and type
     */
    List<WidgetConfiguration> findByOrganizationIdAndWidgetTypeOrderByCreatedAtDesc(
            Long organizationId, WidgetType widgetType);

    /**
     * Find widget by ID with organization check
     */
    Optional<WidgetConfiguration> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * Find widget by key with organization check
     */
    Optional<WidgetConfiguration> findByWidgetKeyAndOrganizationId(String widgetKey, Long organizationId);

    /**
     * Count widgets for a business
     */
    long countByBusinessId(Long businessId);

    /**
     * Count widgets for an organization
     */
    long countByOrganizationId(Long organizationId);

    /**
     * Count active widgets for a business
     */
    long countByBusinessIdAndIsActiveTrue(Long businessId);

    /**
     * Check if widget key exists
     */
    boolean existsByWidgetKey(String widgetKey);

    // ================================================================
    // ANALYTICS QUERIES
    // ================================================================

    /**
     * Increment impression count atomically
     */
    @Modifying
    @Query("UPDATE WidgetConfiguration w SET w.totalImpressions = w.totalImpressions + 1, " +
            "w.lastImpressionAt = CURRENT_TIMESTAMP WHERE w.widgetKey = :widgetKey")
    int incrementImpressions(@Param("widgetKey") String widgetKey);

    /**
     * Increment click count atomically
     */
    @Modifying
    @Query("UPDATE WidgetConfiguration w SET w.totalClicks = w.totalClicks + 1, " +
            "w.lastClickAt = CURRENT_TIMESTAMP WHERE w.widgetKey = :widgetKey")
    int incrementClicks(@Param("widgetKey") String widgetKey);

    /**
     * Get total impressions for an organization
     */
    @Query("SELECT COALESCE(SUM(w.totalImpressions), 0) FROM WidgetConfiguration w " +
            "WHERE w.organization.id = :organizationId")
    Long getTotalImpressionsByOrganization(@Param("organizationId") Long organizationId);

    /**
     * Get total clicks for an organization
     */
    @Query("SELECT COALESCE(SUM(w.totalClicks), 0) FROM WidgetConfiguration w " +
            "WHERE w.organization.id = :organizationId")
    Long getTotalClicksByOrganization(@Param("organizationId") Long organizationId);

    /**
     * Get top performing widgets by CTR for an organization
     */
    @Query("SELECT w FROM WidgetConfiguration w WHERE w.organization.id = :organizationId " +
            "AND w.totalImpressions > 100 " +
            "ORDER BY (CAST(w.totalClicks AS double) / CAST(w.totalImpressions AS double)) DESC")
    List<WidgetConfiguration> findTopPerformingWidgets(
            @Param("organizationId") Long organizationId);

    // ================================================================
    // BUSINESS-LEVEL QUERIES
    // ================================================================

    /**
     * Find all widgets for a specific business
     */
    List<WidgetConfiguration> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /**
     * Find active widgets for a business
     */
    List<WidgetConfiguration> findByBusinessIdAndIsActiveTrueOrderByCreatedAtDesc(Long businessId);

    /**
     * Delete all widgets for a business (cascade handled)
     */
    @Modifying
    @Query("DELETE FROM WidgetConfiguration w WHERE w.business.id = :businessId")
    void deleteAllByBusinessId(@Param("businessId") Long businessId);

    // ================================================================
    // ADMIN/SYSTEM QUERIES
    // ================================================================

    /**
     * Find all active widgets (for system monitoring)
     */
    List<WidgetConfiguration> findByIsActiveTrueOrderByTotalImpressionsDesc();

    /**
     * Find widgets with high impression counts (popular widgets)
     */
    @Query("SELECT w FROM WidgetConfiguration w WHERE w.totalImpressions > :threshold " +
            "ORDER BY w.totalImpressions DESC")
    List<WidgetConfiguration> findPopularWidgets(@Param("threshold") Long threshold);
}