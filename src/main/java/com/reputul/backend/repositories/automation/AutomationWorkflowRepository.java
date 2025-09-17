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
     * Find workflow by ID and organization (tenant scoping)
     */
    @Query("SELECT w FROM AutomationWorkflow w WHERE w.id = :id AND w.organization.id = :orgId")
    Optional<AutomationWorkflow> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Count active workflows by organization
     */
    Long countByOrganizationAndIsActiveTrue(Organization organization);
}