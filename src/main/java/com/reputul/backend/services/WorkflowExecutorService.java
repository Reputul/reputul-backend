package com.reputul.backend.services;

import com.reputul.backend.dto.SendReviewRequestDto;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.repositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Workflow Execution Engine
 * Handles the actual execution of automation workflows
 * Rewritten to align with existing automation models and services
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutorService {

    private final AutomationExecutionRepository executionRepository;
    private final AutomationWorkflowRepository workflowRepository;
    private final CustomerRepository customerRepository;
    private final ReviewRequestService reviewRequestService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final TriggerProcessorService triggerProcessorService;

    /**
     * Execute a workflow for a customer
     * Main entry point for workflow execution
     */
    @Transactional
    public boolean executeWorkflow(AutomationExecution execution) {
        log.info("Starting workflow execution {} for customer {}",
                execution.getId(), execution.getCustomer().getName());

        try {
            // Update execution status
            execution.setStatus(AutomationExecution.ExecutionStatus.RUNNING);
            execution.setStartedAt(OffsetDateTime.now());
            execution = executionRepository.save(execution);

            // Get workflow and customer
            AutomationWorkflow workflow = execution.getWorkflow();
            Customer customer = execution.getCustomer();

            // Log execution start
            log.info("Executing workflow '{}' (trigger: {}) for customer {} in business {}",
                    workflow.getName(),
                    execution.getTriggerEvent(),
                    customer.getName(),
                    customer.getBusiness().getName());

            // Re-validate workflow conditions before execution
            if (!triggerProcessorService.evaluateWorkflowConditions(workflow, customer)) {
                log.info("Workflow conditions no longer met for customer {}, marking as completed", customer.getName());
                completeExecution(execution, "Workflow conditions no longer met");
                return true; // Not a failure, just conditions changed
            }

            // Execute workflow actions based on type and configuration
            boolean success = executeWorkflowActions(execution, workflow, customer);

            // Update final execution status
            if (success) {
                completeExecution(execution, "Workflow completed successfully");
                return true;
            } else {
                failExecution(execution, "Workflow action execution failed");
                return false;
            }

        } catch (Exception e) {
            log.error("Error executing workflow {}: {}", execution.getId(), e.getMessage(), e);
            failExecution(execution, "Exception during workflow execution: " + e.getMessage());
            return false;
        }
    }

    /**
     * Execute workflow actions based on workflow configuration
     */
    private boolean executeWorkflowActions(AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {
        Map<String, Object> actions = workflow.getActions();

        if (actions == null || actions.isEmpty()) {
            // Fallback to trigger-type based default action
            return executeDefaultActionByTriggerType(execution, workflow, customer);
        }

        boolean anyActionSucceeded = false;
        Map<String, Object> executionResults = new HashMap<>();

        // Execute each defined action
        for (Map.Entry<String, Object> actionEntry : actions.entrySet()) {
            String actionType = actionEntry.getKey();
            Object actionConfig = actionEntry.getValue();

            try {
                log.debug("Executing action '{}' for workflow {}", actionType, workflow.getName());

                ActionExecutionResult result = executeAction(actionType, actionConfig, execution, workflow, customer);

                if (result.isSuccess()) {
                    anyActionSucceeded = true;
                    executionResults.put(actionType, result.getData());
                    log.info("Action '{}' executed successfully for customer {}", actionType, customer.getName());
                } else {
                    executionResults.put(actionType, Map.of("error", result.getErrorMessage()));
                    log.warn("Action '{}' failed for customer {}: {}", actionType, customer.getName(), result.getErrorMessage());
                }

            } catch (Exception e) {
                log.error("Failed to execute action '{}' for customer {}: {}", actionType, customer.getName(), e.getMessage());
                executionResults.put(actionType, Map.of("error", e.getMessage()));
            }
        }

        // Update execution data with results
        Map<String, Object> currentExecutionData = execution.getExecutionData() != null ?
                execution.getExecutionData() : new HashMap<>();
        currentExecutionData.put("action_results", executionResults);
        currentExecutionData.put("execution_completed_at", OffsetDateTime.now().toString());
        execution.setExecutionData(currentExecutionData);
        executionRepository.save(execution);

        return anyActionSucceeded;
    }

    /**
     * Execute default action based on workflow trigger type
     */
    private boolean executeDefaultActionByTriggerType(AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {
        log.info("No actions defined, executing default action for trigger type: {}", workflow.getTriggerType());

        switch (workflow.getTriggerType()) {
            case CUSTOMER_CREATED:
                return executeWelcomeAction(execution, workflow, customer);

            case SERVICE_COMPLETED:
                return executeReviewRequestAction(execution, workflow, customer);

            case REVIEW_COMPLETED:
                return executeThankYouAction(execution, workflow, customer);

            default:
                log.warn("No default action defined for trigger type: {}", workflow.getTriggerType());
                return false;
        }
    }

    /**
     * Execute individual action based on its type
     */
    private ActionExecutionResult executeAction(String actionType, Object actionConfig,
                                                AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {

        if (!(actionConfig instanceof Map)) {
            return ActionExecutionResult.failure("Invalid action config - expected Map, got " + actionConfig.getClass().getSimpleName());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) actionConfig;

        // Check if action is enabled
        Boolean enabled = (Boolean) config.get("enabled");
        if (Boolean.FALSE.equals(enabled)) {
            return ActionExecutionResult.success(Map.of("status", "skipped", "reason", "action disabled"));
        }

        switch (actionType.toLowerCase()) {
            case "send_email":
            case "email":
                return executeEmailAction(config, customer, execution);

            case "send_sms":
            case "sms":
                return executeSmsAction(config, customer, execution);

            case "send_review_request":
            case "review_request":
                return executeReviewRequestActionFromConfig(config, customer, execution);

            case "delay":
                return executeDelayAction(config, customer, execution);

            case "webhook":
                return executeWebhookAction(config, customer, execution);

            case "update_customer":
                return executeUpdateCustomerAction(config, customer, execution);

            default:
                return ActionExecutionResult.failure("Unknown action type: " + actionType);
        }
    }

    // =========================
    // SPECIFIC ACTION IMPLEMENTATIONS
    // =========================

    /**
     * Execute email action
     */
    private ActionExecutionResult executeEmailAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String templateType = (String) config.get("template_type");
            String deliveryMethod = (String) config.getOrDefault("delivery_method", "EMAIL");

            if (templateType != null) {
                EmailTemplate.TemplateType type = EmailTemplate.TemplateType.valueOf(templateType.toUpperCase());
                boolean success = emailService.sendFollowUpEmail(customer, type);

                if (success) {
                    return ActionExecutionResult.success(Map.of(
                            "method", "email",
                            "template_type", templateType,
                            "sent_at", OffsetDateTime.now().toString()
                    ));
                } else {
                    return ActionExecutionResult.failure("Email sending failed");
                }
            } else {
                // Default to review request email
                boolean success = emailService.sendReviewRequestWithTemplate(customer);
                return success ?
                        ActionExecutionResult.success(Map.of("method", "email", "template", "default")) :
                        ActionExecutionResult.failure("Default email sending failed");
            }

        } catch (Exception e) {
            return ActionExecutionResult.failure("Email action failed: " + e.getMessage());
        }
    }

    /**
     * Execute SMS action
     */
    private ActionExecutionResult executeSmsAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            // Check SMS eligibility first
            SmsService.SmsEligibilityResult eligibility = smsService.getSmsEligibility(customer);
            if (!eligibility.isEligible()) {
                return ActionExecutionResult.failure("SMS not eligible: " + eligibility.getReason());
            }

            String messageType = (String) config.getOrDefault("message_type", "review_request");

            switch (messageType) {
                case "review_request":
                    SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);
                    if (result.isSuccess()) {
                        return ActionExecutionResult.success(Map.of(
                                "method", "sms",
                                "message_type", messageType,
                                "message_sid", result.getMessageSid(),
                                "sent_at", OffsetDateTime.now().toString()
                        ));
                    } else {
                        return ActionExecutionResult.failure("SMS sending failed: " + result.getErrorMessage());
                    }

                case "follow_up":
                    String followUpType = (String) config.getOrDefault("follow_up_type", "general");
                    SmsService.SmsResult followUpResult = smsService.sendFollowUpSms(customer, followUpType);
                    return followUpResult.isSuccess() ?
                            ActionExecutionResult.success(Map.of("method", "sms", "follow_up_type", followUpType)) :
                            ActionExecutionResult.failure("Follow-up SMS failed: " + followUpResult.getErrorMessage());

                default:
                    return ActionExecutionResult.failure("Unknown SMS message type: " + messageType);
            }

        } catch (Exception e) {
            return ActionExecutionResult.failure("SMS action failed: " + e.getMessage());
        }
    }

    /**
     * Execute review request action from configuration
     */
    private ActionExecutionResult executeReviewRequestActionFromConfig(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String deliveryMethod = (String) config.getOrDefault("delivery_method", "EMAIL");
            Long templateId = config.get("template_id") != null ?
                    ((Number) config.get("template_id")).longValue() : null;

            switch (deliveryMethod.toUpperCase()) {
                case "EMAIL":
                    if (templateId != null) {
                        SendReviewRequestDto requestDto = new SendReviewRequestDto();
                        requestDto.setCustomerId(customer.getId());
                        requestDto.setTemplateId(templateId);
                        reviewRequestService.sendReviewRequest(customer.getBusiness().getUser(), requestDto);
                    } else {
                        reviewRequestService.sendReviewRequestWithDefaultTemplate(customer.getBusiness().getUser(), customer.getId());
                    }
                    break;

                case "SMS":
                    reviewRequestService.sendSmsReviewRequest(customer.getBusiness().getUser(), customer.getId());
                    break;

                default:
                    return ActionExecutionResult.failure("Unknown delivery method: " + deliveryMethod);
            }

            return ActionExecutionResult.success(Map.of(
                    "delivery_method", deliveryMethod,
                    "template_id", templateId,
                    "sent_at", OffsetDateTime.now().toString()
            ));

        } catch (Exception e) {
            return ActionExecutionResult.failure("Review request action failed: " + e.getMessage());
        }
    }

    /**
     * Execute delay action (scheduling future execution)
     */
    private ActionExecutionResult executeDelayAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        // Delay actions should be handled by the scheduler, not here during execution
        // This is essentially a no-op that logs the delay was processed
        Integer delayDays = config.get("delay_days") != null ? ((Number) config.get("delay_days")).intValue() : 0;
        Integer delayHours = config.get("delay_hours") != null ? ((Number) config.get("delay_hours")).intValue() : 0;

        log.info("Delay action processed for customer {} - would delay {}d {}h",
                customer.getName(), delayDays, delayHours);

        return ActionExecutionResult.success(Map.of(
                "delay_days", delayDays,
                "delay_hours", delayHours,
                "processed_at", OffsetDateTime.now().toString()
        ));
    }

    /**
     * Execute webhook action
     */
    private ActionExecutionResult executeWebhookAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        String webhookUrl = (String) config.get("webhook_url");
        String method = (String) config.getOrDefault("method", "POST");

        if (webhookUrl == null) {
            return ActionExecutionResult.failure("Webhook URL not configured");
        }

        // TODO: Implement actual HTTP webhook calls
        log.info("Webhook action would call {} {} for customer {} with data: {}",
                method, webhookUrl, customer.getName(), config);

        return ActionExecutionResult.success(Map.of(
                "webhook_url", webhookUrl,
                "method", method,
                "executed_at", OffsetDateTime.now().toString()
        ));
    }

    /**
     * Execute customer update action
     */
    private ActionExecutionResult executeUpdateCustomerAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        // This could update customer tags, notes, or other properties
        // For now, just log the action
        log.info("Update customer action for customer {}: {}", customer.getName(), config);

        return ActionExecutionResult.success(Map.of(
                "customer_id", customer.getId(),
                "updates", config,
                "updated_at", OffsetDateTime.now().toString()
        ));
    }

    // =========================
    // DEFAULT TRIGGER-BASED ACTIONS
    // =========================

    /**
     * Execute welcome action for new customers
     */
    private boolean executeWelcomeAction(AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {
        try {
            log.info("Executing welcome action for new customer: {}", customer.getName());

            // Send welcome email if customer has email
            if (customer.getEmail() != null) {
                // Use a welcome template or default template
                boolean emailSent = emailService.sendReviewRequestWithTemplate(customer);

                if (emailSent) {
                    updateExecutionData(execution, "welcome_email_sent", true);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Welcome action failed for customer {}: {}", customer.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute review request action for completed services
     */
    private boolean executeReviewRequestAction(AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {
        try {
            log.info("Executing review request action for customer: {}", customer.getName());

            // Check for SMS preference first, then fall back to email
            if (customer.canReceiveSms()) {
                reviewRequestService.sendSmsReviewRequest(customer.getBusiness().getUser(), customer.getId());
                updateExecutionData(execution, "review_request_method", "SMS");
            } else if (customer.getEmail() != null) {
                reviewRequestService.sendReviewRequestWithDefaultTemplate(customer.getBusiness().getUser(), customer.getId());
                updateExecutionData(execution, "review_request_method", "EMAIL");
            } else {
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("Review request action failed for customer {}: {}", customer.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute thank you action for completed reviews
     */
    private boolean executeThankYouAction(AutomationExecution execution, AutomationWorkflow workflow, Customer customer) {
        try {
            log.info("Executing thank you action for customer: {}", customer.getName());

            // Send thank you email
            if (customer.getEmail() != null) {
                boolean emailSent = emailService.sendThankYouEmail(customer);

                if (emailSent) {
                    updateExecutionData(execution, "thank_you_sent", true);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Thank you action failed for customer {}: {}", customer.getName(), e.getMessage());
            return false;
        }
    }

    // =========================
    // EXECUTION STATUS MANAGEMENT
    // =========================

    /**
     * Complete workflow execution successfully
     */
    private void completeExecution(AutomationExecution execution, String message) {
        execution.setStatus(AutomationExecution.ExecutionStatus.COMPLETED);
        execution.setCompletedAt(OffsetDateTime.now());

        updateExecutionData(execution, "completion_message", message);
        updateExecutionData(execution, "completed_at", OffsetDateTime.now().toString());

        executionRepository.save(execution);
        log.info("Workflow execution {} completed: {}", execution.getId(), message);
    }

    /**
     * Mark workflow execution as failed
     */
    private void failExecution(AutomationExecution execution, String errorMessage) {
        execution.setStatus(AutomationExecution.ExecutionStatus.FAILED);
        execution.setErrorMessage(errorMessage);
        execution.setCompletedAt(OffsetDateTime.now());

        updateExecutionData(execution, "error_message", errorMessage);
        updateExecutionData(execution, "failed_at", OffsetDateTime.now().toString());

        executionRepository.save(execution);
        log.error("Workflow execution {} failed: {}", execution.getId(), errorMessage);
    }

    /**
     * Update execution data with key-value pair
     */
    private void updateExecutionData(AutomationExecution execution, String key, Object value) {
        Map<String, Object> executionData = execution.getExecutionData() != null ?
                execution.getExecutionData() : new HashMap<>();
        executionData.put(key, value);
        execution.setExecutionData(executionData);
    }

    // =========================
    // RESULT CLASSES
    // =========================

    /**
     * Result of an individual action execution
     */
    public static class ActionExecutionResult {
        private final boolean success;
        private final String errorMessage;
        private final Map<String, Object> data;

        private ActionExecutionResult(boolean success, String errorMessage, Map<String, Object> data) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.data = data != null ? data : new HashMap<>();
        }

        public static ActionExecutionResult success(Map<String, Object> data) {
            return new ActionExecutionResult(true, null, data);
        }

        public static ActionExecutionResult failure(String errorMessage) {
            return new ActionExecutionResult(false, errorMessage, null);
        }

        public boolean isSuccess() { return success; }
        public String getErrorMessage() { return errorMessage; }
        public Map<String, Object> getData() { return data; }
    }
}