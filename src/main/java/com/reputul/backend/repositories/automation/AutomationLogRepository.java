package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AutomationLogRepository extends JpaRepository<AutomationLog, Long> {

    List<AutomationLog> findByExecutionIdOrderByCreatedAtAsc(Long executionId);

    List<AutomationLog> findByWorkflowIdAndCreatedAtAfterOrderByCreatedAtDesc(Long workflowId, OffsetDateTime since);

    List<AutomationLog> findByLogLevelAndCreatedAtAfterOrderByCreatedAtDesc(AutomationLog.LogLevel logLevel,
                                                                            OffsetDateTime since);
}