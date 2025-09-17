package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AutomationExecutionRepository extends JpaRepository<AutomationExecution, Long> {

    /**
     * Find executions by workflow
     */
    List<AutomationExecution> findByWorkflowOrderByCreatedAtDesc(AutomationWorkflow workflow);

    /**
     * Find executions by workflow and date range
     */
    List<AutomationExecution> findByWorkflowAndCreatedAtBetweenOrderByCreatedAtDesc(
            AutomationWorkflow workflow, OffsetDateTime startDate, OffsetDateTime endDate);

    /**
     * Find executions by customer
     */
    List<AutomationExecution> findByCustomerOrderByCreatedAtDesc(Customer customer);

    /**
     * Find pending executions for processing
     */
    List<AutomationExecution> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            AutomationExecution.ExecutionStatus status, OffsetDateTime cutoffTime);

    /**
     * Count executions by workflow and status
     */
    Long countByWorkflowAndStatus(AutomationWorkflow workflow, AutomationExecution.ExecutionStatus status);

    /**
     * Get execution metrics for workflow
     */
    @Query("""
        SELECT 
            e.status,
            COUNT(e) as count
        FROM AutomationExecution e 
        WHERE e.workflow = :workflow 
        AND e.createdAt >= :since
        GROUP BY e.status
        """)
    List<Object[]> getExecutionMetrics(@Param("workflow") AutomationWorkflow workflow,
                                       @Param("since") OffsetDateTime since);
}