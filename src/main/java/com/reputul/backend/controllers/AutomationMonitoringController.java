package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/automation/monitoring")
@RequiredArgsConstructor
@Slf4j
public class AutomationMonitoringController {

    private final AutomationExecutionRepository executionRepository;
    private final AutomationWorkflowRepository workflowRepository;

    @GetMapping("/executions")
    @PreAuthorize("hasRole('USER')")
    @Transactional(readOnly = true)  // Keep session open for lazy loading
    public ResponseEntity<Map<String, Object>> getRecentExecutions(
            @CurrentUser User user,
            @RequestParam(defaultValue = "24") int hoursBack,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String status) {

        Map<String, Object> result = new HashMap<>();

        try {
            OffsetDateTime since = OffsetDateTime.now().minusHours(hoursBack);
            List<AutomationExecution> executions = executionRepository.findRecentExecutions(
                    user.getOrganization().getId(), since);

            // Filter by status if specified
            if (status != null) {
                AutomationExecution.ExecutionStatus statusEnum = AutomationExecution.ExecutionStatus.valueOf(status.toUpperCase());
                executions = executions.stream()
                        .filter(e -> e.getStatus() == statusEnum)
                        .toList();
            }

            // Limit results
            if (executions.size() > limit) {
                executions = executions.subList(0, limit);
            }

            // Convert to DTOs to avoid lazy loading issues
            result.put("executions", executions.stream()
                    .map(this::convertToExecutionSummary)
                    .toList());
            result.put("totalFound", executions.size());
            result.put("hoursBack", hoursBack);
            result.put("since", since);

        } catch (Exception e) {
            log.error("Error getting recent executions: {}", e.getMessage());
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/metrics")
    @PreAuthorize("hasRole('USER')")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getExecutionMetrics(
            @CurrentUser User user,
            @RequestParam(defaultValue = "24") int hoursBack) {

        Map<String, Object> metrics = new HashMap<>();

        try {
            OffsetDateTime since = OffsetDateTime.now().minusHours(hoursBack);

            List<Object[]> rawMetrics = executionRepository.getExecutionMetrics(
                    user.getOrganization().getId(), since);

            Map<String, Long> statusCounts = new HashMap<>();
            long totalExecutions = 0;

            for (Object[] metric : rawMetrics) {
                AutomationExecution.ExecutionStatus status = (AutomationExecution.ExecutionStatus) metric[0];
                Long count = (Long) metric[1];
                statusCounts.put(status.toString(), count);
                totalExecutions += count;
            }

            metrics.put("totalExecutions", totalExecutions);
            metrics.put("statusBreakdown", statusCounts);
            metrics.put("successRate", calculateSuccessRate(statusCounts, totalExecutions));
            metrics.put("hoursBack", hoursBack);
            metrics.put("since", since);

            long totalWorkflows = workflowRepository.countByOrganizationAndIsActiveTrue(user.getOrganization());
            metrics.put("totalActiveWorkflows", totalWorkflows);

        } catch (Exception e) {
            log.error("Error getting execution metrics: {}", e.getMessage());
            metrics.put("error", e.getMessage());
        }

        return ResponseEntity.ok(metrics);
    }

    @PutMapping("/workflows/{workflowId}/toggle")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggleWorkflow(
            @CurrentUser User user,
            @PathVariable Long workflowId) {

        Map<String, Object> result = new HashMap<>();

        try {
            var workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                    .orElseThrow(() -> new RuntimeException("Workflow not found"));

            boolean newStatus = !workflow.getIsActive();
            workflow.setIsActive(newStatus);
            workflowRepository.save(workflow);

            result.put("success", true);
            result.put("workflowId", workflowId);
            result.put("workflowName", workflow.getName());
            result.put("newStatus", newStatus ? "ACTIVE" : "PAUSED");
            result.put("message", "Workflow " + (newStatus ? "activated" : "paused") + " successfully");

            log.info("User {} {} workflow {}", user.getId(), newStatus ? "activated" : "paused", workflowId);

        } catch (Exception e) {
            log.error("Error toggling workflow {}: {}", workflowId, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // Convert execution to safe DTO
    private Map<String, Object> convertToExecutionSummary(AutomationExecution execution) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("id", execution.getId());
        summary.put("workflowName", execution.getWorkflow().getName());
        summary.put("customerName", execution.getCustomer().getName());
        summary.put("status", execution.getStatus().toString());
        summary.put("triggerEvent", execution.getTriggerEvent());
        summary.put("createdAt", execution.getCreatedAt());
        summary.put("scheduledFor", execution.getScheduledFor());
        summary.put("startedAt", execution.getStartedAt());
        summary.put("completedAt", execution.getCompletedAt());

        if (execution.getErrorMessage() != null) {
            summary.put("errorMessage", execution.getErrorMessage());
        }

        return summary;
    }

    private double calculateSuccessRate(Map<String, Long> statusCounts, long totalExecutions) {
        if (totalExecutions == 0) return 0.0;

        long completed = statusCounts.getOrDefault("COMPLETED", 0L);
        return (double) completed / totalExecutions * 100.0;
    }
}