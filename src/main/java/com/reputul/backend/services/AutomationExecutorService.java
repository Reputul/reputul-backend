package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationWorkflow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AutomationExecutorService
 *
 * Executes the actual workflow actions (send emails, SMS, etc.)
 * Called by AutomationSchedulerService when it's time to run a workflow
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationExecutorService {

    private final ReviewRequestService reviewRequestService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final EmailTemplateService emailTemplateService;

    /**
     * Execute a workflow's actions for a specific execution
     *
     * @param execution The execution context containing workflow, customer, and trigger data
     * @return true if execution was successful, false otherwise
     */
    public boolean executeWorkflow(AutomationExecution execution) {
        AutomationWorkflow workflow = execution.getWorkflow();
        Customer customer = execution.getCustomer();

        log.info("Executing workflow '{}' for customer {} (execution: {})",
                workflow.getName(), customer.getName(), execution.getId());

        try {
            Map<String, Object> actions = workflow.getActions();
            if (actions == null || actions.isEmpty()) {
                log.warn("No actions defined for workflow {}", workflow.getId());
                return false;
            }

            boolean anyActionSucceeded = false;

            // Process each action type
            for (Map.Entry<String, Object> action : actions.entrySet()) {
                String actionType = action.getKey();
                Object actionConfig = action.getValue();

                try {
                    boolean actionResult = executeAction(actionType, actionConfig, customer, execution);
                    if (actionResult) {
                        anyActionSucceeded = true;
                    }
                } catch (Exception e) {
                    log.error("Failed to execute action '{}' for execution {}: {}",
                            actionType, execution.getId(), e.getMessage());
                }
            }

            return anyActionSucceeded;

        } catch (Exception e) {
            log.error("Error executing workflow for execution {}: {}", execution.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Execute a specific action based on its type
     */
    private boolean executeAction(String actionType, Object actionConfig, Customer customer, AutomationExecution execution) {
        log.debug("Executing action '{}' for customer {}", actionType, customer.getId());

        if (!(actionConfig instanceof Map)) {
            log.warn("Invalid action config for action '{}' - expected Map, got {}",
                    actionType, actionConfig.getClass().getSimpleName());
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) actionConfig;

        // Check if action is enabled
        Boolean enabled = (Boolean) config.get("enabled");
        if (Boolean.FALSE.equals(enabled)) {
            log.debug("Action '{}' is disabled, skipping", actionType);
            return true; // Not enabled is not a failure
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
                return executeReviewRequestAction(config, customer, execution);

            case "delay":
                return executeDelayAction(config, customer, execution);

            case "webhook":
                return executeWebhookAction(config, customer, execution);

            default:
                log.warn("Unknown action type: {}", actionType);
                return false;
        }
    }

    /**
     * Execute email action
     */
    private boolean executeEmailAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String templateType = (String) config.get("template_type");
            String templateName = (String) config.get("template_name");

            if (templateType != null) {
                // Use template type for standard templates
                EmailTemplate.TemplateType type = EmailTemplate.TemplateType.valueOf(templateType.toUpperCase());
                return emailService.sendFollowUpEmail(customer, type);

            } else if (templateName != null) {
                // Use custom template by name
                return emailService.sendReviewRequestWithTemplate(customer);

            } else {
                // Default to review request
                return emailService.sendReviewRequestWithTemplate(customer);
            }

        } catch (Exception e) {
            log.error("Failed to execute email action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute SMS action
     */
    private boolean executeSmsAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            // Check SMS eligibility
            SmsService.SmsEligibilityResult eligibility = smsService.getSmsEligibility(customer);
            if (!eligibility.isEligible()) {
                log.info("Customer {} not eligible for SMS: {}", customer.getId(), eligibility.getReason());
                return false;
            }

            String messageType = (String) config.getOrDefault("message_type", "review_request");

            switch (messageType) {
                case "review_request":
                    SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);
                    return result.isSuccess();

                case "follow_up":
                    String followUpType = (String) config.getOrDefault("follow_up_type", "general");
                    SmsService.SmsResult followUpResult = smsService.sendFollowUpSms(customer, followUpType);
                    return followUpResult.isSuccess();

                case "thank_you":
                    // TODO: Implement thank you SMS
                    log.info("Thank you SMS not yet implemented");
                    return true;

                default:
                    log.warn("Unknown SMS message type: {}", messageType);
                    return false;
            }

        } catch (Exception e) {
            log.error("Failed to execute SMS action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute review request action (handles both email and SMS based on preference)
     */
    private boolean executeReviewRequestAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String deliveryMethod = (String) config.getOrDefault("delivery_method", "EMAIL");

            switch (deliveryMethod.toUpperCase()) {
                case "EMAIL":
                    return reviewRequestService.sendReviewRequestWithDefaultTemplate(
                            customer.getBusiness().getUser(), customer.getId()) != null;

                case "SMS":
                    return reviewRequestService.sendSmsReviewRequest(
                            customer.getBusiness().getUser(), customer.getId()) != null;

                case "BOTH":
                    // Send email first, then SMS if email fails
                    try {
                        return reviewRequestService.sendReviewRequestWithDefaultTemplate(
                                customer.getBusiness().getUser(), customer.getId()) != null;
                    } catch (Exception emailError) {
                        log.warn("Email failed, trying SMS for customer {}: {}",
                                customer.getId(), emailError.getMessage());
                        return reviewRequestService.sendSmsReviewRequest(
                                customer.getBusiness().getUser(), customer.getId()) != null;
                    }

                default:
                    log.warn("Unknown delivery method: {}", deliveryMethod);
                    return false;
            }

        } catch (Exception e) {
            log.error("Failed to execute review request action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute delay action (used for multi-step workflows)
     */
    private boolean executeDelayAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        // Delay actions are handled by the scheduler, not the executor
        // This is a no-op that always succeeds
        log.debug("Delay action processed for customer {}", customer.getId());
        return true;
    }

    /**
     * Execute webhook action (call external systems)
     */
    private boolean executeWebhookAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String webhookUrl = (String) config.get("webhook_url");
            String method = (String) config.getOrDefault("method", "POST");

            if (webhookUrl == null) {
                log.warn("Webhook URL not configured for webhook action");
                return false;
            }

            // TODO: Implement actual HTTP webhook calls
            log.info("Webhook action would call {} {} for customer {}", method, webhookUrl, customer.getId());

            // For now, just log and return success
            return true;

        } catch (Exception e) {
            log.error("Failed to execute webhook action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Build context data for template rendering
     */
    private Map<String, Object> buildTemplateContext(Customer customer, AutomationExecution execution) {
        return Map.of(
                "customer", customer,
                "business", customer.getBusiness(),
                "execution", execution,
                "triggerEvent", execution.getTriggerEvent(),
                "triggerData", execution.getTriggerData()
        );
    }

    /**
     * Log execution metrics for monitoring
     */
    private void logExecutionMetrics(String actionType, boolean success, Customer customer) {
        if (success) {
            log.info("Successfully executed {} action for customer {}", actionType, customer.getId());
        } else {
            log.warn("Failed to execute {} action for customer {}", actionType, customer.getId());
        }

        // TODO: Could send metrics to monitoring system here
    }
}