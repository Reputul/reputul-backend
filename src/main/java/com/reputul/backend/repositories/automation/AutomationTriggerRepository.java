package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationTrigger;
import com.reputul.backend.models.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationTriggerRepository extends JpaRepository<AutomationTrigger, Long> {

    /**
     * Find triggers by organization (tenant scoping)
     */
    List<AutomationTrigger> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    /**
     * Find active triggers by event type and organization
     */
    List<AutomationTrigger> findByOrganizationAndEventTypeAndIsActiveTrueOrderByCreatedAtDesc(
            Organization organization, String eventType);

    /**
     * Find all active triggers by organization
     */
    List<AutomationTrigger> findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(Organization organization);

    /**
     * Find trigger by ID and organization (tenant scoping)
     */
    @Query("SELECT t FROM AutomationTrigger t WHERE t.id = :id AND t.organization.id = :orgId")
    Optional<AutomationTrigger> findByIdAndOrganizationId(@Param("id") Long id, @Param("orgId") Long orgId);

    /**
     * Count active triggers by organization
     */
    Long countByOrganizationAndIsActiveTrue(Organization organization);

    /**
     * Find triggers by workflow with organization validation
     */
    @Query("""
        SELECT t FROM AutomationTrigger t 
        JOIN t.workflow w
        WHERE w.id = :workflowId 
        AND t.organization.id = :orgId
        ORDER BY t.createdAt DESC
        """)
    List<AutomationTrigger> findByWorkflowIdAndOrganizationId(
            @Param("workflowId") Long workflowId,
            @Param("orgId") Long orgId);
}