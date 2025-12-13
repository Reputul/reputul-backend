///*
// * ARCHIVED 2025-12-13
// * This automation controller has been replaced with simple campaign presets.
// * See CampaignController for the new simplified approach.
// *
// * Can be re-enabled by uncommenting if advanced automation features are needed.
// */
//
//package com.reputul.backend.controllers;
//
//import com.reputul.backend.dto.automation.AutomationWorkflowDto;
//import com.reputul.backend.models.User;
//import com.reputul.backend.models.automation.AutomationWorkflow;
//import com.reputul.backend.models.automation.AutomationExecution;
//import com.reputul.backend.models.automation.WorkflowTemplate;
//import com.reputul.backend.services.AutomationService;
//import com.reputul.backend.services.AutomationTriggerService;
//import com.reputul.backend.security.CurrentUser;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.security.core.Authentication;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
///**
// * Automation Management REST API
// * Provides endpoints for managing automation workflows and executions
// */
//@RestController
//@RequestMapping("/api/v1/automation")
//@RequiredArgsConstructor
//@Slf4j
//public class AutomationController {
//
//    private final AutomationService automationService;
//    private final AutomationTriggerService triggerService;
//
//    // =========================
//    // WORKFLOW MANAGEMENT
//    // =========================
//
//    /**
//     * Get all workflows for user's organization
//     * GET /api/automation/workflows
//     */
//    @GetMapping("/workflows")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<List<AutomationWorkflowDto>> getWorkflows(
//            @CurrentUser User user,
//            @RequestParam(required = false) Long businessId) {
//
//        List<AutomationWorkflowDto> workflows = automationService.getWorkflows(user, businessId);
//        return ResponseEntity.ok(workflows);
//    }
//
//    /**
//     * Get single workflow
//     * GET /api/automation/workflows/{workflowId}
//     */
//    @GetMapping("/workflows/{workflowId}")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<AutomationWorkflowDto> getWorkflow(
//            @CurrentUser User user,
//            @PathVariable Long workflowId) {
//
//        AutomationWorkflowDto workflow = automationService.getWorkflowDto(user, workflowId);
//        return ResponseEntity.ok(workflow);
//    }
//
//    /**
//     * Create new workflow
//     * POST /api/automation/workflows
//     */
//    @PostMapping("/workflows")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<AutomationWorkflow> createWorkflow(
//            @CurrentUser User user,
//            @Valid @RequestBody AutomationService.CreateWorkflowRequest request) {
//
//        log.info("Creating workflow '{}' for user {}", request.getName(), user.getId());
//
//        AutomationWorkflow workflow = automationService.createWorkflow(user, request);
//        return ResponseEntity.ok(workflow);
//    }
//
//    /**
//     * Create workflow from template
//     * POST /api/automation/workflows/from-template
//     */
//    @PostMapping("/workflows/from-template")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<AutomationWorkflow> createWorkflowFromTemplate(
//            @CurrentUser User user,
//            @RequestParam Long templateId,
//            @RequestBody(required = false) Map<String, Object> customizations) {
//
//        log.info("Creating workflow from template {} for user {}", templateId, user.getId());
//
//        AutomationWorkflow workflow = automationService.createWorkflowFromTemplate(
//                user, templateId, customizations);
//        return ResponseEntity.ok(workflow);
//    }
//
//    /**
//     * Update workflow status (activate/deactivate)
//     * PUT /api/automation/workflows/{workflowId}/status
//     */
//    @PutMapping("/workflows/{workflowId}/status")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<AutomationWorkflow> updateWorkflowStatus(
//            @CurrentUser User user,
//            @PathVariable Long workflowId,
//            @RequestBody Map<String, Boolean> request) {
//
//        boolean isActive = request.getOrDefault("isActive", false);
//        AutomationWorkflow workflow = automationService.updateWorkflowStatus(user, workflowId, isActive);
//        return ResponseEntity.ok(workflow);
//    }
//
//    /**
//     * Get workflow metrics
//     * GET /api/automation/workflows/{workflowId}/metrics
//     */
//    @GetMapping("/workflows/{workflowId}/metrics")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<AutomationService.WorkflowMetrics> getWorkflowMetrics(
//            @CurrentUser User user,
//            @PathVariable Long workflowId,
//            @RequestParam(defaultValue = "30") int days) {
//
//        AutomationService.WorkflowMetrics metrics = automationService.getWorkflowMetrics(user, workflowId, days);
//        return ResponseEntity.ok(metrics);
//    }
//
//    // =========================
//    // WORKFLOW EXECUTION
//    // =========================
//
//    /**
//     * Manually trigger workflow for customer
//     * POST /api/automation/workflows/{workflowId}/trigger
//     */
//    @PostMapping("/workflows/{workflowId}/trigger")
//    @PreAuthorize("hasRole('USER')")
//    @Transactional
//    public ResponseEntity<Map<String, Object>> triggerWorkflow(
//            @CurrentUser User user,
//            @PathVariable Long workflowId,
//            @RequestBody TriggerWorkflowRequest request) {
//
//        log.info("Manually triggering workflow {} for customer {}", workflowId, request.getCustomerId());
//
//        try {
//            AutomationExecution execution = automationService.triggerWorkflow(
//                    user,
//                    workflowId,
//                    request.getCustomerId(),
//                    "MANUAL_TRIGGER",
//                    Map.of("triggered_by", "user_interface", "reason", request.getReason())
//            );
//
//            // Return DTO instead of entity
//            Map<String, Object> response = new HashMap<>();
//            response.put("success", true);
//            response.put("executionId", execution.getId());
//            response.put("workflowName", execution.getWorkflow().getName());
//            response.put("customerName", execution.getCustomer().getName());
//            response.put("status", execution.getStatus().toString());
//            response.put("scheduledFor", execution.getScheduledFor());
//            response.put("message", "Workflow triggered successfully");
//
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            log.error("Error triggering workflow: {}", e.getMessage());
//            return ResponseEntity.ok(Map.of(
//                    "success", false,
//                    "error", e.getMessage()
//            ));
//        }
//    }
//
//    @PostMapping("/workflows/{workflowId}/bulk-trigger")
//    @PreAuthorize("hasRole('USER')")
//    @Transactional
//    public ResponseEntity<Map<String, Object>> bulkTriggerWorkflow(
//            @CurrentUser User user,
//            @PathVariable Long workflowId,
//            @RequestBody BulkTriggerRequest request) {
//
//        log.info("Bulk triggering workflow {} for {} customers", workflowId, request.getCustomerIds().size());
//
//        try {
//            List<AutomationExecution> executions = automationService.bulkTriggerWorkflow(
//                    user, workflowId, request.getCustomerIds(), "BULK_MANUAL_TRIGGER");
//
//            Map<String, Object> response = new HashMap<>();
//            response.put("success", true);
//            response.put("totalTriggered", executions.size());
//            response.put("workflowId", workflowId);
//            response.put("executions", executions.stream()
//                    .map(e -> Map.of(
//                            "executionId", e.getId(),
//                            "customerId", e.getCustomer().getId(),
//                            "customerName", e.getCustomer().getName(),
//                            "status", e.getStatus().toString()
//                    ))
//                    .toList());
//
//            return ResponseEntity.ok(response);
//
//        } catch (Exception e) {
//            log.error("Error bulk triggering workflow: {}", e.getMessage());
//            return ResponseEntity.ok(Map.of(
//                    "success", false,
//                    "error", e.getMessage()
//            ));
//        }
//    }
//
//
//    // =========================
//    // WORKFLOW TEMPLATES
//    // =========================
//
//    /**
//     * Get available workflow templates
//     * GET /api/automation/templates
//     */
//    @GetMapping("/templates")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<List<WorkflowTemplate>> getWorkflowTemplates(
//            @RequestParam(required = false) String category) {
//
//        List<WorkflowTemplate> templates = automationService.getWorkflowTemplates(category);
//        return ResponseEntity.ok(templates);
//    }
//
//    /**
//     * Create custom workflow template
//     * POST /api/automation/templates
//     */
//    @PostMapping("/templates")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<WorkflowTemplate> createWorkflowTemplate(
//            @Valid @RequestBody AutomationService.CreateTemplateRequest request) {
//
//        WorkflowTemplate template = automationService.createWorkflowTemplate(request);
//        return ResponseEntity.ok(template);
//    }
//
//    // =========================
//    // EVENT TRIGGERS
//    // =========================
//
//    /**
//     * Manually trigger service completed event
//     * POST /api/automation/events/service-completed
//     */
//    @PostMapping("/events/service-completed")
//    @PreAuthorize("hasRole('USER')")
//    public ResponseEntity<String> triggerServiceCompletedEvent(
//            @RequestBody ServiceCompletedEventRequest request) {
//
//        // TODO: Get customer by ID and validate ownership
//        // triggerService.onServiceCompleted(customer, request.getServiceType());
//
//        log.info("Service completed event triggered for customer {} (service: {})",
//                request.getCustomerId(), request.getServiceType());
//
//        return ResponseEntity.ok("Event processing initiated");
//    }
//
//    /**
//     * Webhook endpoint for external triggers
//     * POST /api/automation/webhook/{webhookKey}
//     */
//    @PostMapping("/webhook/{webhookKey}")
//    public ResponseEntity<String> handleWebhook(
//            @PathVariable String webhookKey,
//            @RequestBody Map<String, Object> webhookData) {
//
//        log.info("Received webhook: {} with data keys: {}", webhookKey, webhookData.keySet());
//
//        if (webhookData.containsKey("customer_id")) {
//            Long customerId = ((Number) webhookData.get("customer_id")).longValue();
//            triggerService.onWebhookReceived(webhookKey, customerId, webhookData);
//            return ResponseEntity.ok("Webhook processed successfully");
//        }
//
//        return ResponseEntity.badRequest().body("Missing customer_id in webhook data");
//    }
//
//    // =========================
//    // REQUEST/RESPONSE CLASSES
//    // =========================
//
//    public static class TriggerWorkflowRequest {
//        private Long customerId;
//        private String reason;
//
//        // Getters and setters
//        public Long getCustomerId() { return customerId; }
//        public void setCustomerId(Long customerId) { this.customerId = customerId; }
//        public String getReason() { return reason; }
//        public void setReason(String reason) { this.reason = reason; }
//    }
//
//    public static class BulkTriggerRequest {
//        private List<Long> customerIds;
//        private String reason;
//
//        // Getters and setters
//        public List<Long> getCustomerIds() { return customerIds; }
//        public void setCustomerIds(List<Long> customerIds) { this.customerIds = customerIds; }
//        public String getReason() { return reason; }
//        public void setReason(String reason) { this.reason = reason; }
//    }
//
//    public static class ServiceCompletedEventRequest {
//        private Long customerId;
//        private String serviceType;
//
//        // Getters and setters
//        public Long getCustomerId() { return customerId; }
//        public void setCustomerId(Long customerId) { this.customerId = customerId; }
//        public String getServiceType() { return serviceType; }
//        public void setServiceType(String serviceType) { this.serviceType = serviceType; }
//    }
//}