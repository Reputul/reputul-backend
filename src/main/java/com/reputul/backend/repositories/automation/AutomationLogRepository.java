package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AutomationLogRepository extends JpaRepository<AutomationLog, Long> {

    /**
     * Find logs by execution ID
     */
    List<AutomationLog> findByExecutionIdOrderByCreatedAtAsc(Long executionId);

    /**
     * Find logs by workflow ID and date range
     */
    List<AutomationLog> findByWorkflowIdAndCreatedAtAfterOrderByCreatedAtDesc(
            Long workflowId, OffsetDateTime since);

    /**
     * Find logs by log level and date range
     */
    List<AutomationLog> findByLogLevelAndCreatedAtAfterOrderByCreatedAtDesc(
            AutomationLog.LogLevel logLevel, OffsetDateTime since);

    /**
     * Find recent error logs across all workflows
     */
    List<AutomationLog> findByLogLevelInAndCreatedAtAfterOrderByCreatedAtDesc(
            List<AutomationLog.LogLevel> logLevels, OffsetDateTime since);
}