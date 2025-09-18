package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Organization;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, Long> {

    // =========================
    // TENANT-SAFE METHODS (Organization Scoped)
    // =========================

    /**
     * CRITICAL: Find due executions with organization scoping
     * This is the most important method - used by scheduler
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE e.status = :status 
        AND (e.scheduledFor IS NULL OR e.scheduledFor <= :now)
        AND w.organization.id = :orgId
        ORDER BY COALESCE(e.scheduledFor, e.createdAt) ASC
        """)
    List<AutomationExecution> findDueExecutions(
            @Param("status") AutomationExecution.ExecutionStatus status,
            @Param("now") OffsetDateTime now,
            @Param("orgId") Long orgId);

    /**
     * Find executions by workflow with organization validation
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE w.id = :workflowId 
        AND w.organization.id = :orgId
        ORDER BY e.createdAt DESC
        """)
    List<AutomationExecution> findByWorkflowIdAndOrganizationId(
            @Param("workflowId") Long workflowId,
            @Param("orgId") Long orgId);

    /**
     * Find executions by customer within organization
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        JOIN e.customer c
        WHERE c.id = :customerId 
        AND w.organization.id = :orgId
        ORDER BY e.createdAt DESC
        """)
    List<AutomationExecution> findByCustomerIdAndOrganizationId(
            @Param("customerId") Long customerId,
            @Param("orgId") Long orgId);

    /**
     * Find executions by status within organization
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE e.status = :status 
        AND w.organization.id = :orgId
        ORDER BY e.createdAt DESC
        """)
    List<AutomationExecution> findByStatusAndOrganizationId(
            @Param("status") AutomationExecution.ExecutionStatus status,
            @Param("orgId") Long orgId);

    /**
     * Find executions by workflow and date range with organization scoping
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE w.id = :workflowId 
        AND w.organization.id = :orgId
        AND e.createdAt BETWEEN :startDate AND :endDate
        ORDER BY e.createdAt DESC
        """)
    List<AutomationExecution> findByWorkflowAndDateRangeAndOrganizationId(
            @Param("workflowId") Long workflowId,
            @Param("orgId") Long orgId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate);

    /**
     * Count executions by workflow and status with organization scoping
     */
    @Query("""
        SELECT COUNT(e) FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE w.id = :workflowId 
        AND w.organization.id = :orgId
        AND e.status = :status
        """)
    Long countByWorkflowAndStatusAndOrganizationId(
            @Param("workflowId") Long workflowId,
            @Param("orgId") Long orgId,
            @Param("status") AutomationExecution.ExecutionStatus status);

    /**
     * Get execution metrics for organization
     */
    @Query("""
        SELECT 
            e.status,
            COUNT(e) as count
        FROM AutomationExecution e 
        JOIN e.workflow w
        WHERE w.organization.id = :orgId
        AND e.createdAt >= :since
        GROUP BY e.status
        """)
    List<Object[]> getExecutionMetricsByOrganization(
            @Param("orgId") Long orgId,
            @Param("since") OffsetDateTime since);

    /**
     * Get execution metrics for specific workflow with organization scoping
     */
    @Query("""
        SELECT 
            e.status,
            COUNT(e) as count
        FROM AutomationExecution e 
        JOIN e.workflow w
        WHERE w.id = :workflowId 
        AND w.organization.id = :orgId
        AND e.createdAt >= :since
        GROUP BY e.status
        """)
    List<Object[]> getWorkflowExecutionMetrics(
            @Param("workflowId") Long workflowId,
            @Param("orgId") Long orgId,
            @Param("since") OffsetDateTime since);

    /**
     * Find execution by ID with organization scoping (for security)
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE e.id = :executionId 
        AND w.organization.id = :orgId
        """)
    Optional<AutomationExecution> findByIdAndOrganizationId(
            @Param("executionId") Long executionId,
            @Param("orgId") Long orgId);

    /**
     * Find stuck executions within organization
     */
    @Query("""
        SELECT e FROM AutomationExecution e 
        JOIN e.workflow w 
        WHERE e.status = 'RUNNING' 
        AND e.startedAt < :stuckThreshold
        AND w.organization.id = :orgId
        ORDER BY e.startedAt ASC
        """)
    List<AutomationExecution> findStuckExecutions(
            @Param("stuckThreshold") OffsetDateTime stuckThreshold,
            @Param("orgId") Long orgId);

    /**
     * Find executions by status and creation date
     */
    List<AutomationExecution> findByStatusAndCreatedAtBefore(
            AutomationExecution.ExecutionStatus status, OffsetDateTime cutoffDate);

    // Add these methods to AutomationExecutionRepository.java

    /**
     * Find recent test executions using native SQL for JSON search
     */
    @Query(value = """
    SELECT e.* FROM automation_executions e 
    JOIN automation_workflows w ON e.workflow_id = w.id 
    WHERE w.organization_id = :orgId 
    AND e.created_at >= :since 
    AND (e.trigger_event LIKE '%TEST%' OR e.trigger_data::text LIKE '%test_execution%')
    ORDER BY e.created_at DESC 
    LIMIT :limit
    """, nativeQuery = true)
    List<AutomationExecution> findRecentTestExecutionsNative(
            @Param("orgId") Long orgId,
            @Param("since") OffsetDateTime since,
            @Param("limit") int limit);

    /**
     * Find recent executions for health monitoring
     */
    @Query("""
    SELECT e FROM AutomationExecution e 
    WHERE e.workflow.organization.id = :orgId 
    AND e.createdAt >= :since 
    ORDER BY e.createdAt DESC
    """)
    List<AutomationExecution> findRecentExecutions(
            @Param("orgId") Long orgId,
            @Param("since") OffsetDateTime since);

    /**
     * Get execution metrics for organization
     */
    @Query("""
    SELECT e.status, COUNT(e) 
    FROM AutomationExecution e 
    WHERE e.workflow.organization.id = :orgId 
    AND e.createdAt >= :since 
    GROUP BY e.status
    """)
    List<Object[]> getExecutionMetrics(
            @Param("orgId") Long orgId,
            @Param("since") OffsetDateTime since);

    // Update the default method:
    default List<AutomationExecution> findRecentTestExecutions(Long orgId, OffsetDateTime since, int limit) {
        return findRecentTestExecutionsNative(orgId, since, limit);
    }

    /**
     * Find executions by workflow and customer
     */
    List<AutomationExecution> findByWorkflowAndCustomer(AutomationWorkflow workflow, Customer customer);

    /**
     * Find executions by workflow ID and customer ID
     */
    @Query("SELECT e FROM AutomationExecution e WHERE e.workflow.id = :workflowId AND e.customer.id = :customerId")
    List<AutomationExecution> findByWorkflowIdAndCustomerId(@Param("workflowId") Long workflowId, @Param("customerId") Long customerId);

    // =========================
    // LEGACY METHODS (Keep for backward compatibility but mark as deprecated)
    // =========================

    /**
     * @deprecated Use findByWorkflowIdAndOrganizationId for tenant safety
     */
    @Deprecated
    List<AutomationExecution> findByWorkflowOrderByCreatedAtDesc(AutomationWorkflow workflow);

    /**
     * @deprecated Use findByCustomerIdAndOrganizationId for tenant safety
     */
    @Deprecated
    List<AutomationExecution> findByCustomerOrderByCreatedAtDesc(Customer customer);

}