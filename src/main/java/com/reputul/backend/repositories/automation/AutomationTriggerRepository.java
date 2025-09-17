package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationTrigger;
import com.reputul.backend.models.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationTriggerRepository extends JpaRepository<AutomationTrigger, Long> {

    /**
     * Find triggers by organization (tenant scoping)
     */
    List<AutomationTrigger> findByOrganizationOrderByCreatedAtDesc(Organization organization);

    /**
     * FIXED: Find active triggers by event type (removed boolean parameter)
     * When using "IsActiveTrue", Spring Data JPA automatically filters for isActive = true
     */
    List<AutomationTrigger> findByEventTypeAndIsActiveTrueOrderByCreatedAtDesc(String eventType);

    /**
     * Alternative method with explicit isActive parameter
     */
    List<AutomationTrigger> findByEventTypeAndIsActiveOrderByCreatedAtDesc(String eventType, boolean isActive);

    /**
     * Find triggers by organization and event type
     */
    List<AutomationTrigger> findByOrganizationAndEventTypeAndIsActiveTrueOrderByCreatedAtDesc(
            Organization organization, String eventType);

    /**
     * Find all active triggers by organization
     */
    List<AutomationTrigger> findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(Organization organization);

    /**
     * Count active triggers by organization
     */
    Long countByOrganizationAndIsActiveTrue(Organization organization);
}