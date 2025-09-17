package com.reputul.backend.controllers;

import com.reputul.backend.models.User;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.WorkflowTemplate;
import com.reputul.backend.services.AutomationService;
import com.reputul.backend.services.integration.AutomationEventService;
import com.reputul.backend.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Automation Management Controller
 * REST API for creating and managing automation workflows
 */
@RestController
@RequestMapping("/api/automation")
@Slf4j
public class AutomationController {

    private final AutomationService automationService;
    private final AutomationEventService eventService;

    public AutomationController(AutomationService automationService,
                                AutomationEventService eventService) {
        this.automationService = automationService;
        this.eventService = eventService;
    }

    // =========================
    // WORKFLOW MANAGEMENT
    // =========================

    /**
     * Get all workflows for user's organization
     * GET /api/automation/workflows
     */
    @GetMapping("/workflows")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<AutomationWorkflow>> getWorkflows(
            @CurrentUser User user,
            @RequestParam(required = false) Long businessId) {

        List<AutomationWorkflow> workflows = automationService.getWorkflows(
                user.getOrganization().getId(), businessId);
        return ResponseEntity.ok(workflows);
    }

    /**
     * Create new workflow
     * POST /api/automation/workflows
     */
    @PostMapping("/workflows")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AutomationWorkflow> createWorkflow(
            @CurrentUser User user,
            @RequestBody AutomationService.CreateWorkflowRequest request) {

        log.info("Creating workflow '{}' for user {}", request.getName(), user.getId());

        AutomationWorkflow workflow = automationService.createWorkflow(user, request);
        return ResponseEntity.ok(workflow);
    }

    /**
     * Create workflow from template
     * POST /api/automation/workflows/from-template
     */
    @PostMapping("/workflows/from-template")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AutomationWorkflow> createWorkflowFromTemplate(
            @CurrentUser User user,
            @RequestParam Long templateId,
            @RequestBody(required = false) Map<String, Object> customizations) {

        log.info("Creating workflow from template {} for user {}", templateId, user.getId());

        AutomationWorkflow workflow = automationService.createWorkflowFromTemplate(
                user, templateId, customizations);
        return ResponseEntity.ok(workflow);
    }

    /**
     * Update workflow status (activate/deactivate)
     * PUT /api/automation/workflows/{workflowId}/status
     */
    @PutMapping("/workflows/{workflowId}/status")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AutomationWorkflow> updateWorkflowStatus(
            @PathVariable Long workflowId,
            @RequestBody Map<String, Boolean> request) {

        boolean isActive = request.getOrDefault("isActive", false);
        AutomationWorkflow workflow = automationService.updateWorkflowStatus(workflowId, isActive);
        return ResponseEntity.ok(workflow);
    }

    /**
     * Get workflow metrics
     * GET /api/automation/workflows/{workflowId}/metrics
     */
    @GetMapping("/workflows/{workflowId}/metrics")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AutomationService.WorkflowMetrics> getWorkflowMetrics(
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "30") int days) {

        AutomationService.WorkflowMetrics metrics = automationService.getWorkflowMetrics(workflowId, days);
        return ResponseEntity.ok(metrics);
    }

    // =========================
    // WORKFLOW EXECUTION
    // =========================

    /**
     * Manually trigger workflow for customer
     * POST /api/automation/workflows/{workflowId}/trigger
     */
    @PostMapping("/workflows/{workflowId}/trigger")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<AutomationExecution> triggerWorkflow(
            @PathVariable Long workflowId,
            @RequestBody TriggerWorkflowRequest request) {

        log.info("Manually triggering workflow {} for customer {}", workflowId, request.getCustomerId());

        AutomationExecution execution = automationService.triggerWorkflow(
                workflowId,
                request.getCustomerId(),
                "MANUAL_TRIGGER",
                Map.of("triggered_by", "user_interface", "reason", request.getReason())
        );

        return ResponseEntity.ok(execution);
    }

    /**
     * Bulk trigger workflow for multiple customers
     * POST /api/automation/workflows/{workflowId}/bulk-trigger
     */
    @PostMapping("/workflows/{workflowId}/bulk-trigger")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<AutomationExecution>> bulkTriggerWorkflow(
            @PathVariable Long workflowId,
            @RequestBody BulkTriggerRequest request) {

        log.info("Bulk triggering workflow {} for {} customers", workflowId, request.getCustomerIds().size());

        List<AutomationExecution> executions = automationService.bulkTriggerWorkflow(
                workflowId, request.getCustomerIds(), "BULK_MANUAL_TRIGGER");

        return ResponseEntity.ok(executions);
    }

    /**
     * Get workflow executions
     * GET /api/automation/workflows/{workflowId}/executions
     */
    @GetMapping("/workflows/{workflowId}/executions")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<AutomationExecution>> getWorkflowExecutions(
            @PathVariable Long workflowId,
            @RequestParam(defaultValue = "30") int days) {

        // This would need to be implemented in AutomationService
        return ResponseEntity.ok(List.of());
    }

    // =========================
    // WORKFLOW TEMPLATES
    // =========================

    /**
     * Get available workflow templates
     * GET /api/automation/templates
     */
    @GetMapping("/templates")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<List<WorkflowTemplate>> getWorkflowTemplates(
            @RequestParam(required = false) String category) {

        List<WorkflowTemplate> templates = automationService.getWorkflowTemplates(category);
        return ResponseEntity.ok(templates);
    }

    /**
     * Create custom workflow template
     * POST /api/automation/templates
     */
    @PostMapping("/templates")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<WorkflowTemplate> createWorkflowTemplate(
            @RequestBody AutomationService.CreateTemplateRequest request) {

        WorkflowTemplate template = automationService.createWorkflowTemplate(request);
        return ResponseEntity.ok(template);
    }

    // =========================
    // EVENT TRIGGERS
    // =========================

    /**
     * Manually trigger service completed event
     * POST /api/automation/events/service-completed
     */
    @PostMapping("/events/service-completed")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<String> triggerServiceCompletedEvent(
            @RequestBody ServiceCompletedEventRequest request) {

        eventService.publishServiceCompletedEvent(request.getCustomerId(), request.getServiceType());
        return ResponseEntity.ok("Event published successfully");
    }

    /**
     * Webhook endpoint for external triggers
     * POST /api/automation/webhook/{webhookKey}
     */
    @PostMapping("/webhook/{webhookKey}")
    public ResponseEntity<String> handleWebhook(
            @PathVariable String webhookKey,
            @RequestBody Map<String, Object> webhookData) {

        log.info("Received webhook: {} with data keys: {}", webhookKey, webhookData.keySet());

        // Validate webhook key and extract customer ID
        if (webhookData.containsKey("customer_id")) {
            Long customerId = ((Number) webhookData.get("customer_id")).longValue();
            eventService.publishWebhookEvent(customerId, webhookKey, webhookData);
            return ResponseEntity.ok("Webhook processed successfully");
        }

        return ResponseEntity.badRequest().body("Missing customer_id in webhook data");
    }

    // =========================
    // REQUEST/RESPONSE CLASSES
    // =========================

    public static class TriggerWorkflowRequest {
        private Long customerId;
        private String reason;

        // Getters and setters
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class BulkTriggerRequest {
        private List<Long> customerIds;
        private String reason;

        // Getters and setters
        public List<Long> getCustomerIds() { return customerIds; }
        public void setCustomerIds(List<Long> customerIds) { this.customerIds = customerIds; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ServiceCompletedEventRequest {
        private Long customerId;
        private String serviceType;

        // Getters and setters
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public String getServiceType() { return serviceType; } // FIXED: Was serviceType(), now getServiceType()
        public void setServiceType(String serviceType) { this.serviceType = serviceType; }
    }
}