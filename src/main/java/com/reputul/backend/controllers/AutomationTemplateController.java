package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.services.AutomationWorkflowTemplateService;
import com.reputul.backend.services.AutomationTestingService;
import com.reputul.backend.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AutomationTemplateController
 *
 * REST API for managing automation workflow templates and testing
 */
@RestController
@RequestMapping("/api/automation/templates")
@RequiredArgsConstructor
@Slf4j
public class AutomationTemplateController {

    private final AutomationWorkflowTemplateService templateService;
    private final AutomationTestingService testingService;

    /**
     * Get recommended workflow templates for user
     * GET /api/automation/templates/recommendations
     */
    @GetMapping("/recommendations")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getRecommendations(@CurrentUser User user) {
        Map<String, Object> recommendations = templateService.getRecommendedTemplates(user);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Create all default workflow templates for user
     * POST /api/automation/templates/create-defaults
     */
    @PostMapping("/create-defaults")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> createDefaultTemplates(@CurrentUser User user) {
        log.info("Creating default automation templates for user {}", user.getId());

        Map<String, Object> result = templateService.createAllDefaultWorkflows(user);
        return ResponseEntity.ok(result);
    }

    /**
     * Get template statistics and usage
     * GET /api/automation/templates/stats
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getTemplateStats(@CurrentUser User user) {
        Map<String, Object> stats = templateService.getTemplateStats(user);
        return ResponseEntity.ok(stats);
    }

    /**
     * Test workflow execution with specific customer
     * POST /api/automation/templates/test-execution
     */
    @PostMapping("/test-execution")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> testWorkflowExecution(
            @CurrentUser User user,
            @RequestBody TestExecutionRequest request) {

        log.info("Testing workflow {} execution for customer {}", request.getWorkflowId(), request.getCustomerId());

        Map<String, Object> result = testingService.testWorkflowExecution(
                user, request.getWorkflowId(), request.getCustomerId());
        return ResponseEntity.ok(result);
    }

    /**
     * Preview what would happen if workflow was triggered
     * POST /api/automation/templates/preview-execution
     */
    @PostMapping("/preview-execution")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> previewWorkflowExecution(
            @CurrentUser User user,
            @RequestBody TestExecutionRequest request) {

        Map<String, Object> preview = testingService.previewWorkflowExecution(
                user, request.getWorkflowId(), request.getCustomerId());
        return ResponseEntity.ok(preview);
    }

    /**
     * Get recent test executions for monitoring
     * GET /api/automation/templates/test-executions
     */
    @GetMapping("/test-executions")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getTestExecutions(
            @CurrentUser User user,
            @RequestParam(defaultValue = "10") int limit) {

        Map<String, Object> executions = testingService.getTestExecutions(user, limit);
        return ResponseEntity.ok(executions);
    }

    /**
     * Get automation system health status
     * GET /api/automation/templates/health
     */
    @GetMapping("/health")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> getAutomationHealth(@CurrentUser User user) {
        Map<String, Object> health = testingService.getAutomationHealth(user);
        return ResponseEntity.ok(health);
    }

    /**
     * Force create templates (for troubleshooting)
     * POST /api/automation/templates/force-create
     */
    @PostMapping("/force-create")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, Object>> forceCreateTemplates(@CurrentUser User user) {
        log.info("Force creating automation templates for user {}", user.getId());

        try {
            templateService.createDefaultWorkflowTemplatesForUser(user);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Templates created successfully"
            ));
        } catch (Exception e) {
            log.error("Error force creating templates: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    // Request/Response classes
    public static class TestExecutionRequest {
        private Long workflowId;
        private Long customerId;

        public Long getWorkflowId() { return workflowId; }
        public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
    }
}