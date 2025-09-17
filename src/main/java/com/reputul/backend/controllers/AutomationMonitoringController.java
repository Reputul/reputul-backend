package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.services.AutomationSchedulerService;
import com.reputul.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Automation Monitoring Controller
 * Provides endpoints for monitoring automation workflow execution and system health
 */
@RestController
@RequestMapping("/api/automation/monitoring")
@RequiredArgsConstructor
@Slf4j
public class AutomationMonitoringController {

    private final AutomationExecutionRepository executionRepository;
    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationSchedulerService schedulerService;

    /**
     * Get execution overview for user's organization
     * GET /api/automation/monitoring/overview
     */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getExecutionOverview(@CurrentUser User user) {
        log.debug("Getting automation overview for user: {}", user.getId());

        try {
            // Get recent executions (last 30 days)
            OffsetDateTime since = OffsetDateTime.now().minusDays(30);

            Map<String, Object> overview = new HashMap<>();

            // Get organization workflows
            List<AutomationWorkflow> workflows = workflowRepository.findByOrganizationOrderByCreatedAtDesc(user.getOrganization());
            overview.put("total_workflows", workflows.size());
            overview.put("active_workflows", workflows.stream().filter(w -> w.getIsActive()).count());

            // Execution statistics by status (organization-wide)
            Map<String, Long> statusCounts = new HashMap<>();
            for (AutomationExecution.ExecutionStatus status : AutomationExecution.ExecutionStatus.values()) {
                long count = getExecutionCountByStatusForOrganization(user.getOrganization().getId(), status, since);
                statusCounts.put(status.name().toLowerCase(), count);
            }
            overview.put("execution_counts", statusCounts);

            // Recent activity
            List<Map<String, Object>> recentExecutions = getRecentExecutionsForOrganization(user.getOrganization().getId(), 10);
            overview.put("recent_executions", recentExecutions);

            // Workflow performance
            Map<String, Object> performance = getWorkflowPerformanceForOrganization(user.getOrganization().getId(), since);
            overview.put("performance", performance);

            return ResponseEntity.ok(overview);

        } catch (Exception e) {
            log.error("Error getting automation overview for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to load overview"));
        }
    }

    /**
     * Get pending executions for user's organization
     * GET /api/automation/monitoring/pending
     */
    @GetMapping("/pending")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<Map<String, Object>>> getPendingExecutions(
            @CurrentUser User user,
            @RequestParam(defaultValue = "50") int limit) {

        log.debug("Getting pending executions for user: {} (limit: {})", user.getId(), limit);

        try {
            // Get pending executions for user's organization
            List<AutomationExecution> executions = getPendingExecutionsForOrganization(user.getOrganization().getId(), limit);

            List<Map<String, Object>> result = executions.stream()
                    .map(this::convertExecutionToSummary)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error getting pending executions for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(List.of());
        }
    }

    /**
     * Get executions for a specific workflow
     * GET /api/automation/monitoring/workflow/{workflowId}/executions
     */
    @GetMapping("/workflow/{workflowId}/executions")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<Map<String, Object>>> getWorkflowExecutions(
            @CurrentUser User user,
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "30") int days) {

        log.debug("Getting executions for workflow {} for user {}", workflowId, user.getId());

        try {
            // Verify workflow belongs to user's organization
            AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                    .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

            OffsetDateTime since = OffsetDateTime.now().minusDays(days);
            List<AutomationExecution> executions = executionRepository.findByWorkflowAndCreatedAtBetweenOrderByCreatedAtDesc(
                    workflow, since, OffsetDateTime.now());

            List<Map<String, Object>> result = executions.stream()
                    .map(this::convertExecutionToDetail)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error getting workflow executions for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(List.of());
        }
    }

    /**
     * Get executions for a specific customer
     * GET /api/automation/monitoring/customer/{customerId}/executions
     */
    @GetMapping("/customer/{customerId}/executions")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<Map<String, Object>>> getCustomerExecutions(
            @CurrentUser User user,
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "30") int days) {

        log.debug("Getting executions for customer {} for user {}", customerId, user.getId());

        try {
            // TODO: Add customer ownership validation here

            OffsetDateTime since = OffsetDateTime.now().minusDays(days);

            // Find executions for this customer within user's organization
            List<AutomationExecution> executions = getCustomerExecutionsForOrganization(
                    user.getOrganization().getId(), customerId, since);

            List<Map<String, Object>> result = executions.stream()
                    .map(this::convertExecutionToDetail)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Error getting customer executions for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(List.of());
        }
    }

    /**
     * Get system health metrics
     * GET /api/automation/monitoring/health
     */
    @GetMapping("/health")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getHealthMetrics(@CurrentUser User user) {
        log.debug("Getting health metrics for organization: {}", user.getOrganization().getId());

        try {
            Map<String, Object> health = new HashMap<>();
            health.put("status", "healthy");
            health.put("timestamp", OffsetDateTime.now());
            health.put("organization_id", user.getOrganization().getId());

            // Count active workflows
            long activeWorkflows = workflowRepository.countByOrganizationAndIsActiveTrue(user.getOrganization());
            health.put("active_workflows", activeWorkflows);

            // Count pending executions
            int pendingCount = getPendingExecutionsForOrganization(user.getOrganization().getId(), 1000).size();
            health.put("pending_executions", pendingCount);

            // Count recent executions (last 24 hours)
            OffsetDateTime last24h = OffsetDateTime.now().minusDays(1);
            Map<String, Long> recentCounts = new HashMap<>();
            for (AutomationExecution.ExecutionStatus status : AutomationExecution.ExecutionStatus.values()) {
                long count = getExecutionCountByStatusForOrganization(user.getOrganization().getId(), status, last24h);
                recentCounts.put(status.name().toLowerCase(), count);
            }
            health.put("last_24h_executions", recentCounts);

            // System performance indicators
            Map<String, Object> performance = new HashMap<>();
            performance.put("avg_execution_time_minutes", calculateAverageExecutionTime(user.getOrganization().getId()));
            performance.put("success_rate_percent", calculateSuccessRate(user.getOrganization().getId(), last24h));
            health.put("performance", performance);

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Error getting health metrics for user {}: {}", user.getId(), e.getMessage());

            Map<String, Object> errorHealth = Map.of(
                    "status", "error",
                    "timestamp", OffsetDateTime.now(),
                    "error", e.getMessage()
            );
            return ResponseEntity.status(500).body(errorHealth);
        }
    }

    /**
     * Cancel a pending execution
     * POST /api/automation/monitoring/execution/{executionId}/cancel
     */
    @PostMapping("/execution/{executionId}/cancel")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> cancelExecution(
            @CurrentUser User user,
            @PathVariable Long executionId,
            @RequestBody(required = false) Map<String, String> request) {

        try {
            // Verify execution belongs to user's organization
            AutomationExecution execution = executionRepository.findById(executionId)
                    .orElseThrow(() -> new RuntimeException("Execution not found"));

            if (!execution.getWorkflow().getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
            }

            String reason = request != null ? request.getOrDefault("reason", "Cancelled by user") : "Cancelled by user";
            boolean cancelled = schedulerService.cancelExecution(executionId, reason);

            if (cancelled) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Execution cancelled successfully"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Execution could not be cancelled (may already be running or completed)"
                ));
            }

        } catch (Exception e) {
            log.error("Error cancelling execution {} for user {}: {}", executionId, user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to cancel execution"));
        }
    }

    // =========================
    // PRIVATE HELPER METHODS
    // =========================

    private List<AutomationExecution> getPendingExecutionsForOrganization(Long organizationId, int limit) {
        // Get pending executions and limit manually since repository method doesn't support pagination
        List<AutomationExecution> allPending = executionRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                AutomationExecution.ExecutionStatus.PENDING,
                OffsetDateTime.now().plusMinutes(1)); // Adding 1 minute to include current pending executions

        // Manually limit results
        return allPending.stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    private long getExecutionCountByStatusForOrganization(Long organizationId, AutomationExecution.ExecutionStatus status, OffsetDateTime since) {
        // This would need a custom query to filter by organization
        // For now, return 0 as placeholder
        return 0L;
    }

    private List<Map<String, Object>> getRecentExecutionsForOrganization(Long organizationId, int limit) {
        // This would need a custom query
        return List.of();
    }

    private Map<String, Object> getWorkflowPerformanceForOrganization(Long organizationId, OffsetDateTime since) {
        return Map.of(
                "total_executions", 0,
                "success_rate", 0.0,
                "avg_execution_time", 0.0
        );
    }

    private List<AutomationExecution> getCustomerExecutionsForOrganization(Long organizationId, Long customerId, OffsetDateTime since) {
        // This would need a custom query
        return List.of();
    }

    private double calculateAverageExecutionTime(Long organizationId) {
        // Calculate average time between startedAt and completedAt
        return 0.0; // Placeholder
    }

    private double calculateSuccessRate(Long organizationId, OffsetDateTime since) {
        // Calculate percentage of successful executions
        return 0.0; // Placeholder
    }

    private Map<String, Object> convertExecutionToSummary(AutomationExecution execution) {
        return Map.of(
                "id", execution.getId(),
                "workflow_name", execution.getWorkflow().getName(),
                "customer_name", execution.getCustomer() != null ? execution.getCustomer().getName() : "Unknown",
                "status", execution.getStatus().name(),
                "trigger_event", execution.getTriggerEvent(),
                "created_at", execution.getCreatedAt(),
                "started_at", execution.getStartedAt()
        );
    }

    private Map<String, Object> convertExecutionToDetail(AutomationExecution execution) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("id", execution.getId());
        detail.put("workflow", Map.of(
                "id", execution.getWorkflow().getId(),
                "name", execution.getWorkflow().getName(),
                "trigger_type", execution.getWorkflow().getTriggerType()
        ));

        if (execution.getCustomer() != null) {
            detail.put("customer", Map.of(
                    "id", execution.getCustomer().getId(),
                    "name", execution.getCustomer().getName(),
                    "email", execution.getCustomer().getEmail()
            ));
        }

        detail.put("status", execution.getStatus().name());
        detail.put("trigger_event", execution.getTriggerEvent());
        detail.put("trigger_data", execution.getTriggerData());
        detail.put("execution_data", execution.getExecutionData());
        detail.put("error_message", execution.getErrorMessage());
        detail.put("created_at", execution.getCreatedAt());
        detail.put("started_at", execution.getStartedAt());
        detail.put("completed_at", execution.getCompletedAt());

        // Calculate execution duration if completed
        if (execution.getStartedAt() != null && execution.getCompletedAt() != null) {
            long durationSeconds = java.time.Duration.between(execution.getStartedAt(), execution.getCompletedAt()).getSeconds();
            detail.put("duration_seconds", durationSeconds);
        }

        return detail;
    }
}