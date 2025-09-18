package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationWorkflowRepository extends JpaRepository<AutomationWorkflow, Long> {

    // =========================
    // TENANT-SAFE METHODS (All properly scoped)
    // =========================

    /**
     * Find workflows by organization (tenant scoping)
     */
    List<AutomationWorkflow> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    /**
     * Find workflows by organization and business
     */
    List<AutomationWorkflow> findByOrganizationAndBusinessOrderByCreatedAtDesc(
            Organization organization, Business business);

    /**
     * Find active workflows by organization
     */
    List<AutomationWorkflow> findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(Organization organization);

    /**
     * Find workflows by trigger type
     */
    List<AutomationWorkflow> findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
            Organization organization, AutomationWorkflow.TriggerType triggerType);

    /**
     * Find workflow by ID and organization (tenant scoping) - CRITICAL for security
     */
    @Query("SELECT w FROM AutomationWorkflow w WHERE w.id = :id AND w.organization.id = :orgId")
    Optional<AutomationWorkflow> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Count active workflows by organization
     */
    Long countByOrganizationAndIsActiveTrue(Organization organization);

    /**
     * Find workflows by business with organization validation (additional safety)
     */
    @Query("""
        SELECT w FROM AutomationWorkflow w 
        WHERE w.business.id = :businessId 
        AND w.organization.id = :orgId
        AND w.isActive = true
        ORDER BY w.createdAt DESC
        """)
    List<AutomationWorkflow> findByBusinessIdAndOrganizationId(
            @Param("businessId") Long businessId,
            @Param("orgId") Long orgId);

    /**
     * Find workflows by name within organization (for uniqueness checks)
     */
    @Query("""
        SELECT w FROM AutomationWorkflow w 
        WHERE w.name = :name 
        AND w.organization.id = :orgId
        """)
    Optional<AutomationWorkflow> findByNameAndOrganizationId(
            @Param("name") String name,
            @Param("orgId") Long orgId);

    /**
     * Count total workflows by organization
     */
    Long countByOrganization(Organization organization);
}