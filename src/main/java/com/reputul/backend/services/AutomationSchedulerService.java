package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AutomationSchedulerService
 *
 * Handles scheduling and execution of automation workflows including:
 * - Delayed executions (e.g., "send review request 3 days after service completion")
 * - Recurring workflows
 * - Scheduled campaigns
 * - Processing pending executions
 *
 * Integrates with Spring's @Scheduled for periodic processing and Quartz for complex scheduling
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationSchedulerService {

    private final AutomationExecutionRepository executionRepository;
    private final AutomationWorkflowRepository workflowRepository;
    private final CustomerRepository customerRepository;
    private final TriggerProcessorService triggerProcessorService;
    private final AutomationExecutorService automationExecutorService;

    // =========================
    // SCHEDULING METHODS
    // =========================

    /**
     * Schedule a workflow execution for a specific customer
     *
     * @param workflow The workflow to execute
     * @param customerId The target customer
     * @param triggerEvent The event that triggered this
     * @param executeAt When to execute (null = immediate)
     * @param triggerData Additional context data
     */
    @Transactional
    public AutomationExecution scheduleWorkflowExecution(
            AutomationWorkflow workflow,
            Long customerId,
            String triggerEvent,
            OffsetDateTime executeAt,
            Map<String, Object> triggerData) {

        log.info("Scheduling workflow '{}' for customer {} at {}",
                workflow.getName(), customerId, executeAt);

        // Get customer for validation
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));

        // Pre-validate workflow conditions
        if (!triggerProcessorService.evaluateWorkflowConditions(workflow, customer)) {
            log.info("Workflow conditions not met for customer {}, skipping", customerId);
            return null;
        }

        // Create execution record
        AutomationExecution execution = AutomationExecution.builder()
                .workflow(workflow)
                .customer(customer)
                .status(executeAt == null ?
                        AutomationExecution.ExecutionStatus.PENDING :
                        AutomationExecution.ExecutionStatus.PENDING)
                .triggerEvent(triggerEvent)
                .triggerData(triggerData != null ? triggerData : new HashMap<>())
                .executionData(buildExecutionData(workflow, executeAt))
                .build();

        execution = executionRepository.save(execution);

        // If immediate execution, process now
        if (executeAt == null || executeAt.isBefore(OffsetDateTime.now().plusMinutes(1))) {
            processExecutionAsync(execution.getId());
        }

        return execution;
    }

    /**
     * Schedule delayed workflow execution (most common use case)
     * e.g., "Send review request 3 days after service completion"
     */
    @Transactional
    public AutomationExecution scheduleDelayedExecution(
            AutomationWorkflow workflow,
            Long customerId,
            String triggerEvent,
            int delayDays,
            int delayHours,
            Map<String, Object> triggerData) {

        OffsetDateTime executeAt = OffsetDateTime.now()
                .plusDays(delayDays)
                .plusHours(delayHours);

        return scheduleWorkflowExecution(workflow, customerId, triggerEvent, executeAt, triggerData);
    }

    /**
     * Schedule workflow based on trigger configuration
     */
    @Transactional
    public AutomationExecution scheduleFromTriggerConfig(
            AutomationWorkflow workflow,
            Long customerId,
            String triggerEvent,
            Map<String, Object> triggerData) {

        Map<String, Object> triggerConfig = workflow.getTriggerConfig();
        OffsetDateTime executeAt = null;

        if (triggerConfig != null) {
            // Extract delay configuration
            Integer delayDays = extractInteger(triggerConfig, "delayDays", 0);
            Integer delayHours = extractInteger(triggerConfig, "delayHours", 0);
            Integer delayMinutes = extractInteger(triggerConfig, "delayMinutes", 0);

            if (delayDays > 0 || delayHours > 0 || delayMinutes > 0) {
                executeAt = OffsetDateTime.now()
                        .plusDays(delayDays)
                        .plusHours(delayHours)
                        .plusMinutes(delayMinutes);
            }

            // Handle specific execution time
            if (triggerConfig.containsKey("executeAtHour")) {
                int hour = extractInteger(triggerConfig, "executeAtHour", 9);
                executeAt = OffsetDateTime.now().plusDays(delayDays > 0 ? delayDays : 1)
                        .withHour(hour).withMinute(0).withSecond(0);
            }
        }

        return scheduleWorkflowExecution(workflow, customerId, triggerEvent, executeAt, triggerData);
    }

    // =========================
    // SCHEDULED PROCESSING
    // =========================

    /**
     * Process pending executions every minute
     * Handles delayed workflows that are now due for execution
     */
    @Scheduled(fixedRate = 60000) // Every 60 seconds
    @Transactional
    public void processPendingExecutions() {
        log.debug("Processing pending automation executions");

        try {
            // Find executions that are due (including those without specific execute time)
            OffsetDateTime now = OffsetDateTime.now();
            List<AutomationExecution> dueExecutions = executionRepository
                    .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                            AutomationExecution.ExecutionStatus.PENDING,
                            now.minusMinutes(1));

            if (!dueExecutions.isEmpty()) {
                log.info("Found {} pending executions to process", dueExecutions.size());

                for (AutomationExecution execution : dueExecutions) {
                    try {
                        if (shouldExecuteNow(execution)) {
                            processExecutionAsync(execution.getId());
                        }
                    } catch (Exception e) {
                        log.error("Error processing execution {}: {}", execution.getId(), e.getMessage());
                        markExecutionFailed(execution.getId(), "Processing error: " + e.getMessage());
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error in scheduled execution processing: {}", e.getMessage(), e);
        }
    }

    /**
     * Clean up old completed/failed executions
     * Runs daily to prevent database bloat
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    @Transactional
    public void cleanupOldExecutions() {
        log.info("Starting cleanup of old automation executions");

        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);

            // Keep only recent executions or failed ones for debugging
            List<AutomationExecution> oldExecutions = executionRepository
                    .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                            AutomationExecution.ExecutionStatus.COMPLETED, cutoff);

            if (!oldExecutions.isEmpty()) {
                log.info("Cleaning up {} old completed executions", oldExecutions.size());
                executionRepository.deleteAll(oldExecutions);
            }

        } catch (Exception e) {
            log.error("Error during execution cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Health check for stuck executions
     * Finds executions that have been running too long
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    @Transactional
    public void checkForStuckExecutions() {
        try {
            OffsetDateTime stuckThreshold = OffsetDateTime.now().minusMinutes(15);

            List<AutomationExecution> stuckExecutions = executionRepository
                    .findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                            AutomationExecution.ExecutionStatus.RUNNING, stuckThreshold);

            for (AutomationExecution execution : stuckExecutions) {
                log.warn("Found stuck execution {} running for over 15 minutes", execution.getId());
                markExecutionFailed(execution.getId(), "Execution timeout - marked as failed after 15 minutes");
            }

        } catch (Exception e) {
            log.error("Error checking for stuck executions: {}", e.getMessage());
        }
    }

    // =========================
    // EXECUTION PROCESSING
    // =========================

    /**
     * Process a single execution asynchronously
     */
    @Async
    public CompletableFuture<Void> processExecutionAsync(Long executionId) {
        try {
            processExecution(executionId);
        } catch (Exception e) {
            log.error("Async execution processing failed for execution {}: {}", executionId, e.getMessage());
            markExecutionFailed(executionId, "Async processing error: " + e.getMessage());
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Process a single execution
     */
    @Transactional
    public void processExecution(Long executionId) {
        log.debug("Processing execution: {}", executionId);

        AutomationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        // Skip if already processed
        if (execution.getStatus() != AutomationExecution.ExecutionStatus.PENDING) {
            log.debug("Execution {} already processed with status: {}", executionId, execution.getStatus());
            return;
        }

        try {
            // Mark as running
            execution.setStatus(AutomationExecution.ExecutionStatus.RUNNING);
            execution.setStartedAt(OffsetDateTime.now());
            execution = executionRepository.save(execution);

            // Re-validate conditions before execution (things may have changed)
            if (!triggerProcessorService.evaluateWorkflowConditions(execution.getWorkflow(), execution.getCustomer())) {
                log.info("Workflow conditions no longer met for execution {}, marking as completed", executionId);
                markExecutionCompleted(executionId, "Conditions no longer met");
                return;
            }

            // Execute the workflow actions
            boolean success = automationExecutorService.executeWorkflow(execution);

            if (success) {
                markExecutionCompleted(executionId, "Successfully executed");
            } else {
                markExecutionFailed(executionId, "Workflow execution returned false");
            }

        } catch (Exception e) {
            log.error("Error executing workflow for execution {}: {}", executionId, e.getMessage(), e);
            markExecutionFailed(executionId, "Execution error: " + e.getMessage());
        }
    }

    // =========================
    // UTILITY METHODS
    // =========================

    /**
     * Determine if an execution should run now based on its configuration
     */
    private boolean shouldExecuteNow(AutomationExecution execution) {
        Map<String, Object> executionData = execution.getExecutionData();

        if (executionData == null) {
            return true; // No specific timing, execute immediately
        }

        // Check if there's a specific execution time
        if (executionData.containsKey("executeAt")) {
            String executeAtStr = (String) executionData.get("executeAt");
            try {
                OffsetDateTime executeAt = OffsetDateTime.parse(executeAtStr);
                return OffsetDateTime.now().isAfter(executeAt);
            } catch (Exception e) {
                log.warn("Failed to parse executeAt time for execution {}: {}", execution.getId(), executeAtStr);
                return true;
            }
        }

        // Check business hours constraint
        if (executionData.containsKey("businessHoursOnly") &&
                Boolean.TRUE.equals(executionData.get("businessHoursOnly"))) {

            int currentHour = OffsetDateTime.now().getHour();
            return currentHour >= 9 && currentHour <= 17; // 9 AM to 5 PM
        }

        return true;
    }

    /**
     * Build execution data from workflow configuration
     */
    private Map<String, Object> buildExecutionData(AutomationWorkflow workflow, OffsetDateTime executeAt) {
        Map<String, Object> executionData = new HashMap<>();

        if (executeAt != null) {
            executionData.put("executeAt", executeAt.toString());
        }

        // Copy relevant trigger config to execution data
        Map<String, Object> triggerConfig = workflow.getTriggerConfig();
        if (triggerConfig != null) {
            if (triggerConfig.containsKey("businessHoursOnly")) {
                executionData.put("businessHoursOnly", triggerConfig.get("businessHoursOnly"));
            }
            if (triggerConfig.containsKey("maxRetries")) {
                executionData.put("maxRetries", triggerConfig.get("maxRetries"));
            }
        }

        executionData.put("scheduledAt", OffsetDateTime.now().toString());
        return executionData;
    }

    /**
     * Mark execution as completed
     */
    @Transactional
    public void markExecutionCompleted(Long executionId, String message) {
        AutomationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        execution.setStatus(AutomationExecution.ExecutionStatus.COMPLETED);
        execution.setCompletedAt(OffsetDateTime.now());

        Map<String, Object> executionData = execution.getExecutionData() != null ?
                execution.getExecutionData() : new HashMap<>();
        executionData.put("completionMessage", message);
        execution.setExecutionData(executionData);

        executionRepository.save(execution);
        log.info("Execution {} marked as completed: {}", executionId, message);
    }

    /**
     * Mark execution as failed
     */
    @Transactional
    public void markExecutionFailed(Long executionId, String errorMessage) {
        AutomationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        execution.setStatus(AutomationExecution.ExecutionStatus.FAILED);
        execution.setErrorMessage(errorMessage);
        execution.setCompletedAt(OffsetDateTime.now());

        executionRepository.save(execution);
        log.error("Execution {} marked as failed: {}", executionId, errorMessage);
    }

    /**
     * Cancel a pending execution
     */
    @Transactional
    public boolean cancelExecution(Long executionId, String reason) {
        AutomationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new RuntimeException("Execution not found: " + executionId));

        if (execution.getStatus() == AutomationExecution.ExecutionStatus.PENDING) {
            execution.setStatus(AutomationExecution.ExecutionStatus.CANCELLED);
            execution.setErrorMessage("Cancelled: " + reason);
            execution.setCompletedAt(OffsetDateTime.now());

            executionRepository.save(execution);
            log.info("Execution {} cancelled: {}", executionId, reason);
            return true;
        }

        return false;
    }

    /**
     * Get execution statistics for monitoring
     */
    public Map<String, Object> getExecutionStats() {
        Map<String, Object> stats = new HashMap<>();

        // Count by status
        for (AutomationExecution.ExecutionStatus status : AutomationExecution.ExecutionStatus.values()) {
            long count = executionRepository.countByWorkflowAndStatus(null, status);
            stats.put("total_" + status.name().toLowerCase(), count);
        }

        // Recent execution metrics (last 24 hours)
        OffsetDateTime since = OffsetDateTime.now().minusDays(1);
        stats.put("executions_last_24h", executionRepository.getExecutionMetrics(null, since));

        return stats;
    }

    // =========================
    // HELPER METHODS
    // =========================

    private Integer extractInteger(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}