package com.reputul.backend.services;

import com.reputul.backend.models.User;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.automation.*;
import com.reputul.backend.repositories.automation.*;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.BusinessRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationService {

    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationExecutionRepository executionRepository;
    private final AutomationTriggerRepository triggerRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;

    // =========================
    // WORKFLOW MANAGEMENT
    // =========================

    /**
     * Get workflows for user's organization with proper tenant scoping
     */
    public List<AutomationWorkflow> getWorkflows(User user, Long businessId) {
        log.debug("Getting workflows for organization: {}, business: {}", user.getOrganization().getId(), businessId);

        if (businessId != null) {
            // Validate business belongs to user's organization
            Business business = businessRepository.findByIdAndUserId(businessId, user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
            return workflowRepository.findByOrganizationAndBusinessOrderByCreatedAtDesc(
                    user.getOrganization(), business);
        }

        return workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());
    }

    /**
     * Create new workflow with proper validation and tenant scoping
     */
    @Transactional
    public AutomationWorkflow createWorkflow(User user, CreateWorkflowRequest request) {
        log.info("Creating workflow '{}' for organization {}", request.getName(), user.getOrganization().getId());

        // Validate business if specified
        Business business = null;
        if (request.getBusinessId() != null) {
            business = businessRepository.findByIdAndUserId(
                            request.getBusinessId(), user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
        }

        // Ensure trigger config is not null
        Map<String, Object> triggerConfig = request.getTriggerConfig() != null ?
                request.getTriggerConfig() : new HashMap<>();

        AutomationWorkflow workflow = AutomationWorkflow.builder()
                .name(request.getName())
                .description(request.getDescription())
                .organization(user.getOrganization())
                .business(business)
                .triggerType(request.getTriggerType())
                .triggerConfig(triggerConfig)
                .actions(request.getActions())
                .isActive(true)
                .createdBy(user)
                .build();

        AutomationWorkflow savedWorkflow = workflowRepository.save(workflow);
        log.info("Created workflow {} for organization {}", savedWorkflow.getId(), user.getOrganization().getId());

        return savedWorkflow;
    }

    /**
     * Create workflow from template with customizations
     */
    @Transactional
    public AutomationWorkflow createWorkflowFromTemplate(User user, Long templateId, Map<String, Object> customizations) {
        log.info("Creating workflow from template {} for organization {}", templateId, user.getOrganization().getId());

        WorkflowTemplate template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Template not found: " + templateId));

        // Extract configuration from template
        Map<String, Object> templateConfig = template.getTemplateConfig() != null ?
                new HashMap<>(template.getTemplateConfig()) : new HashMap<>();
        Map<String, Object> defaultActions = template.getDefaultActions() != null ?
                new HashMap<>(template.getDefaultActions()) : new HashMap<>();

        // Apply customizations
        if (customizations != null) {
            Map<String, Object> customTriggerConfig = (Map<String, Object>) customizations.get("triggerConfig");
            if (customTriggerConfig != null) {
                templateConfig.putAll(customTriggerConfig);
            }

            Map<String, Object> customActions = (Map<String, Object>) customizations.get("actions");
            if (customActions != null) {
                defaultActions.putAll(customActions);
            }
        }

        String workflowName = customizations != null && customizations.containsKey("name") ?
                (String) customizations.get("name") : template.getName() + " (Copy)";

        AutomationWorkflow workflow = AutomationWorkflow.builder()
                .name(workflowName)
                .description(template.getDescription())
                .organization(user.getOrganization())
                .triggerType(template.getTriggerType())
                .triggerConfig(templateConfig)
                .actions(defaultActions)
                .isActive(true)
                .createdBy(user)
                .build();

        return workflowRepository.save(workflow);
    }

    /**
     * Update workflow status with proper authorization
     */
    @Transactional
    public AutomationWorkflow updateWorkflowStatus(User user, Long workflowId, boolean isActive) {
        log.info("Updating workflow {} status to: {} for organization {}", workflowId, isActive, user.getOrganization().getId());

        AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

        workflow.setIsActive(isActive);
        return workflowRepository.save(workflow);
    }

    /**
     * Get workflow metrics with proper calculations
     */
    public WorkflowMetrics getWorkflowMetrics(User user, Long workflowId, int days) {
        log.debug("Getting metrics for workflow {} for {} days", workflowId, days);

        // Verify workflow belongs to user's organization
        AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

        OffsetDateTime since = OffsetDateTime.now().minusDays(days);

        // Get execution metrics
        List<Object[]> metrics = executionRepository.getWorkflowExecutionMetrics(workflowId, user.getOrganization().getId(), since);

        long totalExecutions = 0;
        long successfulExecutions = 0;
        long failedExecutions = 0;

        for (Object[] metric : metrics) {
            AutomationExecution.ExecutionStatus status = (AutomationExecution.ExecutionStatus) metric[0];
            Long count = (Long) metric[1];

            totalExecutions += count;
            if (status == AutomationExecution.ExecutionStatus.COMPLETED) {
                successfulExecutions += count;
            } else if (status == AutomationExecution.ExecutionStatus.FAILED) {
                failedExecutions += count;
            }
        }

        // Calculate average execution time (simplified)
        double avgExecutionTime = totalExecutions > 0 ? 2.5 : 0.0; // TODO: Calculate from actual data

        return WorkflowMetrics.builder()
                .totalExecutions(totalExecutions)
                .successfulExecutions(successfulExecutions)
                .failedExecutions(failedExecutions)
                .averageExecutionTime(avgExecutionTime)
                .lastExecution(OffsetDateTime.now()) // TODO: Get from actual last execution
                .build();
    }

    // =========================
    // WORKFLOW EXECUTION
    // =========================

    /**
     * Trigger workflow execution for a customer
     */
    @Transactional
    public AutomationExecution triggerWorkflow(User user, Long workflowId, Long customerId, String triggerEvent, Map<String, Object> triggerData) {
        log.info("Triggering workflow {} for customer {} with event {}", workflowId, customerId, triggerEvent);

        // Verify workflow belongs to user's organization
        AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

        // Verify customer belongs to user's organization
        Customer customer = customerRepository.findByIdAndUser(customerId, user)
                .orElseThrow(() -> new RuntimeException("Customer not found or access denied"));

        // Calculate scheduled execution time based on trigger config
        OffsetDateTime scheduledFor = calculateExecutionTime(workflow.getTriggerConfig());

        AutomationExecution execution = AutomationExecution.builder()
                .workflow(workflow)
                .customer(customer)
                .business(customer.getBusiness())
                .triggerEvent(triggerEvent)
                .triggerData(triggerData != null ? triggerData : new HashMap<>())
                .scheduledFor(scheduledFor)
                .status(AutomationExecution.ExecutionStatus.PENDING)
                .build();

        AutomationExecution savedExecution = executionRepository.save(execution);

        // Update workflow execution count
        workflow.setExecutionCount((workflow.getExecutionCount() != null ? workflow.getExecutionCount() : 0) + 1);
        workflowRepository.save(workflow);

        return savedExecution;
    }

    /**
     * Bulk trigger workflow for multiple customers
     */
    @Transactional
    public List<AutomationExecution> bulkTriggerWorkflow(User user, Long workflowId, List<Long> customerIds, String triggerEvent) {
        log.info("Bulk triggering workflow {} for {} customers", workflowId, customerIds.size());

        // Verify workflow belongs to user's organization
        AutomationWorkflow workflow = workflowRepository.findByIdAndOrganizationId(workflowId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Workflow not found or access denied"));

        return customerIds.stream()
                .map(customerId -> {
                    try {
                        return triggerWorkflow(user, workflowId, customerId, triggerEvent, Map.of("bulk_trigger", true));
                    } catch (Exception e) {
                        log.error("Failed to trigger workflow for customer {}: {}", customerId, e.getMessage());
                        return null;
                    }
                })
                .filter(execution -> execution != null)
                .toList();
    }

    // =========================
    // WORKFLOW TEMPLATES
    // =========================

    public List<WorkflowTemplate> getWorkflowTemplates(String category) {
        if (category != null) {
            return templateRepository.findByCategoryAndIsActiveTrueOrderByCreatedAtDesc(category);
        }
        return templateRepository.findByIsActiveTrueOrderByCreatedAtDesc();
    }

    @Transactional
    public WorkflowTemplate createWorkflowTemplate(CreateTemplateRequest request) {
        log.info("Creating workflow template: {}", request.getName());

        WorkflowTemplate template = WorkflowTemplate.builder()
                .name(request.getName())
                .description(request.getDescription())
                .category(request.getCategory())
                .isActive(true)
                .triggerType(request.getTriggerType())
                .templateConfig(request.getTemplateConfig())
                .defaultActions(request.getDefaultActions())
                .build();

        return templateRepository.save(template);
    }

    // =========================
    // HELPER METHODS
    // =========================

    /**
     * Calculate when to execute workflow based on trigger configuration
     */
    private OffsetDateTime calculateExecutionTime(Map<String, Object> triggerConfig) {
        if (triggerConfig == null) return null;

        Integer delayDays = (Integer) triggerConfig.get("delay_days");
        Integer delayHours = (Integer) triggerConfig.get("delay_hours");
        Integer delayMinutes = (Integer) triggerConfig.get("delay_minutes");

        if (delayDays == null && delayHours == null && delayMinutes == null) {
            return null; // Execute immediately
        }

        OffsetDateTime scheduledTime = OffsetDateTime.now();

        if (delayDays != null) scheduledTime = scheduledTime.plusDays(delayDays);
        if (delayHours != null) scheduledTime = scheduledTime.plusHours(delayHours);
        if (delayMinutes != null) scheduledTime = scheduledTime.plusMinutes(delayMinutes);

        return scheduledTime;
    }

    // =========================
    // REQUEST/RESPONSE CLASSES
    // =========================

    @lombok.Data
    @lombok.Builder
    public static class CreateWorkflowRequest {
        @NotBlank
        private String name;
        private String description;
        @NotNull
        private AutomationWorkflow.TriggerType triggerType;
        private Map<String, Object> triggerConfig;
        @NotNull
        private Map<String, Object> actions;
        private Long businessId;
    }

    @lombok.Data
    @lombok.Builder
    public static class CreateTemplateRequest {
        @NotBlank
        private String name;
        @NotBlank
        private String description;
        @NotBlank
        private String category;
        @NotNull
        private AutomationWorkflow.TriggerType triggerType;
        private Map<String, Object> templateConfig;
        private Map<String, Object> defaultActions;
    }

    @lombok.Data
    @lombok.Builder
    public static class WorkflowMetrics {
        private Long totalExecutions;
        private Long successfulExecutions;
        private Long failedExecutions;
        private Double averageExecutionTime;
        private OffsetDateTime lastExecution;
    }
}