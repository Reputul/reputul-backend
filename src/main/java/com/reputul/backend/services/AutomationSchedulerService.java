package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.OrganizationRepository;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * - Processing pending executions
 * - Execution state management
 * - System health monitoring and cleanup
 */
@Deprecated
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationSchedulerService {

    private final AutomationExecutionRepository executionRepository;
    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationExecutorService automationExecutorService;
    private final CustomerRepository customerRepository;
    private final OrganizationRepository organizationRepository;
    private final MeterRegistry meterRegistry;

    // =========================
    // SCHEDULING METHODS
    // =========================

    /**
     * Schedule a workflow execution for a specific customer
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

        // Get customer and validate it belongs to the same organization as workflow
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found: " + customerId));

        // Validate customer belongs to same organization as workflow
        if (!customer.getUser().getOrganization().getId().equals(workflow.getOrganization().getId())) {
            throw new RuntimeException("Customer and workflow belong to different organizations");
        }

        // Create execution record
        AutomationExecution execution = AutomationExecution.builder()
                .workflow(workflow)
                .customer(customer)
                .business(customer.getBusiness())
                .status(AutomationExecution.ExecutionStatus.PENDING)
                .triggerEvent(triggerEvent)
                .triggerData(triggerData != null ? triggerData : new HashMap<>())
                .scheduledFor(executeAt)
                .executionData(buildExecutionData(workflow, executeAt))
                .build();

        execution = executionRepository.save(execution);

        // Record scheduling metric
        Counter.builder("automation.executions.scheduled")
                .description("Number of executions scheduled")
                .tag("trigger_event", triggerEvent)
                .tag("workflow_id", workflow.getId().toString())
                .register(meterRegistry)
                .increment();

        // If immediate execution, process now
        if (executeAt == null || executeAt.isBefore(OffsetDateTime.now().plusMinutes(1))) {
            processExecutionAsync(execution.getId());
        }

        return execution;
    }

    /**
     * Schedule delayed workflow execution (most common use case)
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
        OffsetDateTime executeAt = calculateExecutionTime(triggerConfig);

        return scheduleWorkflowExecution(workflow, customerId, triggerEvent, executeAt, triggerData);
    }

    // =========================
    // SCHEDULED PROCESSING
    // =========================

    /**
     * Process pending executions every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    @Transactional
    public void processPendingExecutions() {
        log.debug("Processing pending automation executions");

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            OffsetDateTime now = OffsetDateTime.now();
            int totalProcessed = 0;

            // Process for each organization to maintain tenant isolation
            List<Organization> organizations = organizationRepository.findAll();

            for (Organization org : organizations) {
                int orgProcessed = processPendingExecutionsForOrganization(org.getId(), now);
                totalProcessed += orgProcessed;
            }

            // Record processing metrics
            Counter.builder("automation.scheduler.runs")
                    .description("Number of scheduler runs")
                    .tag("executions_processed", String.valueOf(totalProcessed))
                    .register(meterRegistry)
                    .increment();

            sample.stop(Timer.builder("automation.scheduler.duration")
                    .description("Time taken to process pending executions")
                    .register(meterRegistry));

        } catch (Exception e) {
            log.error("Error in scheduled execution processing: {}", e.getMessage(), e);
            sample.stop(Timer.builder("automation.scheduler.duration")
                    .tag("status", "error")
                    .register(meterRegistry));
        }
    }

    /**
     * Process pending executions for a specific organization
     */
    private int processPendingExecutionsForOrganization(Long orgId, OffsetDateTime now) {
        List<AutomationExecution> dueExecutions = executionRepository.findDueExecutions(
                AutomationExecution.ExecutionStatus.PENDING, now, orgId);

        log.debug("Found {} due executions for organization {}", dueExecutions.size(), orgId);

        for (AutomationExecution execution : dueExecutions) {
            try {
                processExecutionAsync(execution.getId());
            } catch (Exception e) {
                log.error("Error processing execution {}: {}", execution.getId(), e.getMessage());
                markExecutionFailed(execution.getId(), e.getMessage());
            }
        }

        return dueExecutions.size();
    }

    /**
     * Clean up old completed/failed executions
     */
    @Scheduled(cron = "0 0 2 * * ?") // 2 AM daily
    @Transactional
    public void cleanupOldExecutions() {
        log.info("Starting cleanup of old automation executions");

        try {
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(30);

            // Find completed executions older than 30 days
            List<AutomationExecution> oldExecutions = executionRepository.findByStatusAndCreatedAtBefore(
                    AutomationExecution.ExecutionStatus.COMPLETED, cutoff);

            if (!oldExecutions.isEmpty()) {
                log.info("Cleaning up {} old completed executions", oldExecutions.size());
                executionRepository.deleteAll(oldExecutions);

                Counter.builder("automation.executions.cleaned")
                        .description("Number of old executions cleaned up")
                        .register(meterRegistry)
                        .increment(oldExecutions.size());
            }

        } catch (Exception e) {
            log.error("Error during execution cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Health check for stuck executions
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    @Transactional
    public void checkForStuckExecutions() {
        try {
            OffsetDateTime stuckThreshold = OffsetDateTime.now().minusMinutes(15);

            // Process each organization separately
            List<Organization> organizations = organizationRepository.findAll();

            for (Organization org : organizations) {
                List<AutomationExecution> stuckExecutions = executionRepository.findStuckExecutions(
                        stuckThreshold, org.getId());

                for (AutomationExecution execution : stuckExecutions) {
                    log.warn("Found stuck execution {} running for over 15 minutes", execution.getId());
                    markExecutionFailed(execution.getId(), "Execution timeout - marked as failed after 15 minutes");

                    Counter.builder("automation.executions.stuck")
                            .description("Number of stuck executions detected")
                            .register(meterRegistry)
                            .increment();
                }
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

        Timer.Sample executionTimer = Timer.start(meterRegistry);

        try {
            // Mark as running
            execution.setStatus(AutomationExecution.ExecutionStatus.RUNNING);
            execution.setStartedAt(OffsetDateTime.now());
            execution = executionRepository.save(execution);

            // Execute the workflow actions
            boolean success = automationExecutorService.executeWorkflow(execution);

            if (success) {
                markExecutionCompleted(executionId, "Successfully executed");
            } else {
                markExecutionFailed(executionId, "Workflow execution returned false");
            }

            executionTimer.stop(Timer.builder("automation.execution.duration")
                    .tag("status", success ? "success" : "failed")
                    .register(meterRegistry));

        } catch (Exception e) {
            log.error("Error executing workflow for execution {}: {}", executionId, e.getMessage(), e);
            markExecutionFailed(executionId, "Execution error: " + e.getMessage());

            executionTimer.stop(Timer.builder("automation.execution.duration")
                    .tag("status", "error")
                    .register(meterRegistry));
        }
    }

    // =========================
    // UTILITY METHODS
    // =========================

    /**
     * Calculate when to execute workflow based on trigger configuration
     */
    private OffsetDateTime calculateExecutionTime(Map<String, Object> triggerConfig) {
        if (triggerConfig == null) return null;

        Integer delayDays = extractInteger(triggerConfig, "delay_days");
        Integer delayHours = extractInteger(triggerConfig, "delay_hours");
        Integer delayMinutes = extractInteger(triggerConfig, "delay_minutes");

        if (delayDays == null && delayHours == null && delayMinutes == null) {
            return null; // Execute immediately
        }

        OffsetDateTime scheduledTime = OffsetDateTime.now();

        if (delayDays != null) scheduledTime = scheduledTime.plusDays(delayDays);
        if (delayHours != null) scheduledTime = scheduledTime.plusHours(delayHours);
        if (delayMinutes != null) scheduledTime = scheduledTime.plusMinutes(delayMinutes);

        // Handle business hours constraint
        if (Boolean.TRUE.equals(triggerConfig.get("business_hours_only"))) {
            scheduledTime = adjustToBusinessHours(scheduledTime);
        }

        return scheduledTime;
    }

    /**
     * Adjust execution time to business hours (9 AM - 5 PM)
     */
    private OffsetDateTime adjustToBusinessHours(OffsetDateTime scheduledTime) {
        int hour = scheduledTime.getHour();

        if (hour < 9) {
            // Too early, move to 9 AM same day
            return scheduledTime.withHour(9).withMinute(0).withSecond(0);
        } else if (hour >= 17) {
            // Too late, move to 9 AM next day
            return scheduledTime.plusDays(1).withHour(9).withMinute(0).withSecond(0);
        }

        return scheduledTime;
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
            if (triggerConfig.containsKey("business_hours_only")) {
                executionData.put("business_hours_only", triggerConfig.get("business_hours_only"));
            }
            if (triggerConfig.containsKey("max_retries")) {
                executionData.put("max_retries", triggerConfig.get("max_retries"));
            }
        }

        executionData.put("scheduled_at", OffsetDateTime.now().toString());
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
        executionData.put("completion_message", message);
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

        try {
            // Get recent stats (last 24 hours)
            OffsetDateTime since = OffsetDateTime.now().minusDays(1);

            // This would need custom repository methods
            stats.put("total_pending", "N/A"); // TODO: Implement custom count queries
            stats.put("total_running", "N/A");
            stats.put("total_completed_24h", "N/A");
            stats.put("total_failed_24h", "N/A");

            stats.put("last_processed", OffsetDateTime.now());

        } catch (Exception e) {
            log.error("Error getting execution stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }

    // =========================
    // HELPER METHODS
    // =========================

    private Integer extractInteger(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }
}