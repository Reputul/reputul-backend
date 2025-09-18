package com.reputul.backend.services;

import com.reputul.backend.models.User;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AutomationTestingService
 *
 * Provides testing utilities for automation workflows
 * Allows users to test workflows before going live
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationTestingService {

    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationExecutionRepository executionRepository;
    private final CustomerRepository customerRepository;
    private final AutomationTriggerService triggerService;
    private final AutomationSchedulerService schedulerService;

    /**
     * Test workflow execution with a specific customer
     */
    public Map<String, Object> testWorkflowExecution(User user, Long workflowId, Long customerId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Validate workflow belongs to user
            AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                    .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

            // Validate customer belongs to user
            Customer customer = customerRepository.findByIdAndUser(customerId, user)
                    .orElseThrow(() -> new RuntimeException("Customer not found or access denied"));

            // Create test execution
            Map<String, Object> triggerData = Map.of(
                    "test_execution", true,
                    "triggered_by", "manual_test",
                    "test_user_id", user.getId(),
                    "test_timestamp", OffsetDateTime.now().toString()
            );

            AutomationExecution execution = schedulerService.scheduleWorkflowExecution(
                    workflow, customerId, "TEST_EXECUTION", null, triggerData);

            result.put("success", true);
            result.put("executionId", execution.getId());
            result.put("workflowName", workflow.getName());
            result.put("customerName", customer.getName());
            result.put("status", execution.getStatus().toString());
            result.put("message", "Test execution initiated successfully");
            result.put("scheduledFor", execution.getScheduledFor());

            log.info("Test execution {} created for workflow {} and customer {}",
                    execution.getId(), workflowId, customerId);

        } catch (Exception e) {
            log.error("Error testing workflow execution: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Preview what would happen if workflow was triggered
     */
    public Map<String, Object> previewWorkflowExecution(User user, Long workflowId, Long customerId) {
        Map<String, Object> preview = new HashMap<>();

        try {
            // Validate workflow and customer
            AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                    .orElseThrow(() -> new RuntimeException("Workflow not found"));

            Customer customer = customerRepository.findByIdAndUser(customerId, user)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            preview.put("workflowName", workflow.getName());
            preview.put("customerName", customer.getName());
            preview.put("triggerType", workflow.getTriggerType().toString());

            // Analyze what would happen
            if (workflow.getDeliveryMethod() != null) {
                preview.put("simpleExecution", true);
                preview.put("deliveryMethod", workflow.getDeliveryMethod().toString());
                preview.put("wouldSend", determineWouldSend(workflow.getDeliveryMethod(), customer));
            } else {
                preview.put("simpleExecution", false);
                preview.put("actions", analyzeActions(workflow.getActions(), customer));
            }

            // Check if customer meets conditions
            preview.put("customerEligible", checkCustomerEligibility(workflow, customer));
            preview.put("readyForAutomation", customer.isReadyForAutomation());
            preview.put("automationAlreadyTriggered", customer.getAutomationTriggered());

            // Timing info
            if (workflow.getTriggerConfig() != null) {
                preview.put("delay", calculateDelay(workflow.getTriggerConfig()));
            }

        } catch (Exception e) {
            log.error("Error previewing workflow execution: {}", e.getMessage());
            preview.put("error", e.getMessage());
        }

        return preview;
    }

    /**
     * Get recent test executions for monitoring
     */
    public Map<String, Object> getTestExecutions(User user, int limit) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get recent executions that were test executions
            OffsetDateTime since = OffsetDateTime.now().minusDays(7);
            List<AutomationExecution> executions = executionRepository.findRecentTestExecutions(
                    user.getOrganization().getId(), since, limit);

            result.put("executions", executions.stream()
                    .map(this::convertExecutionToSummary)
                    .toList());
            result.put("totalFound", executions.size());
            result.put("since", since);

        } catch (Exception e) {
            log.error("Error getting test executions for user {}: {}", user.getId(), e.getMessage());
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Health check for automation system
     */
    public Map<String, Object> getAutomationHealth(User user) {
        Map<String, Object> health = new HashMap<>();

        try {
            // Basic workflow stats
            List<AutomationWorkflow> workflows = workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());
            health.put("totalWorkflows", workflows.size());
            health.put("activeWorkflows", workflows.stream().mapToInt(w -> w.getIsActive() ? 1 : 0).sum());

            // Recent execution stats
            OffsetDateTime last24h = OffsetDateTime.now().minusDays(1);
            List<AutomationExecution> recentExecutions = executionRepository.findRecentExecutions(
                    user.getOrganization().getId(), last24h);

            health.put("executions24h", recentExecutions.size());
            health.put("successful24h", recentExecutions.stream()
                    .mapToInt(e -> e.getStatus() == AutomationExecution.ExecutionStatus.COMPLETED ? 1 : 0)
                    .sum());
            health.put("failed24h", recentExecutions.stream()
                    .mapToInt(e -> e.getStatus() == AutomationExecution.ExecutionStatus.FAILED ? 1 : 0)
                    .sum());

            // System status
            health.put("systemStatus", "healthy");
            health.put("lastCheck", OffsetDateTime.now());

        } catch (Exception e) {
            log.error("Error getting automation health for user {}: {}", user.getId(), e.getMessage());
            health.put("systemStatus", "error");
            health.put("error", e.getMessage());
        }

        return health;
    }

    // Helper methods

    private String determineWouldSend(AutomationWorkflow.DeliveryMethod deliveryMethod, Customer customer) {
        return switch (deliveryMethod) {
            case EMAIL -> customer.getEmail() != null ? "Yes - has email" : "No - no email address";
            case SMS -> customer.canReceiveSms() ? "Yes - SMS eligible" : "No - not SMS eligible";
            case BOTH -> "Would try email first, SMS if email fails";
        };
    }

    private Map<String, Object> analyzeActions(Map<String, Object> actions, Customer customer) {
        Map<String, Object> analysis = new HashMap<>();
        if (actions != null) {
            analysis.put("actionCount", actions.size());
            analysis.put("actionTypes", actions.keySet());
            // Could add more detailed analysis here
        }
        return analysis;
    }

    private boolean checkCustomerEligibility(AutomationWorkflow workflow, Customer customer) {
        // Basic eligibility check
        if (workflow.getConditions() == null) return true;

        Map<String, Object> conditions = workflow.getConditions();

        if (Boolean.TRUE.equals(conditions.get("has_email")) &&
                (customer.getEmail() == null || customer.getEmail().trim().isEmpty())) {
            return false;
        }

        if (Boolean.TRUE.equals(conditions.get("has_phone")) &&
                (customer.getPhone() == null || customer.getPhone().trim().isEmpty())) {
            return false;
        }

        return true;
    }

    private String calculateDelay(Map<String, Object> triggerConfig) {
        Integer days = (Integer) triggerConfig.get("delay_days");
        Integer hours = (Integer) triggerConfig.get("delay_hours");
        Integer minutes = (Integer) triggerConfig.get("delay_minutes");

        StringBuilder delay = new StringBuilder();
        if (days != null && days > 0) delay.append(days).append(" days ");
        if (hours != null && hours > 0) delay.append(hours).append(" hours ");
        if (minutes != null && minutes > 0) delay.append(minutes).append(" minutes");

        return delay.length() > 0 ? delay.toString().trim() : "Immediate";
    }

    private Map<String, Object> convertExecutionToSummary(AutomationExecution execution) {
        return Map.of(
                "id", execution.getId(),
                "workflowName", execution.getWorkflow().getName(),
                "customerName", execution.getCustomer().getName(),
                "status", execution.getStatus().toString(),
                "createdAt", execution.getCreatedAt(),
                "completedAt", execution.getCompletedAt(),
                "triggerEvent", execution.getTriggerEvent()
        );
    }
}