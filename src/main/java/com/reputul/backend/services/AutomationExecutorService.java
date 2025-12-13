package com.reputul.backend.services;

import com.reputul.backend.config.WebClientConfig.WebhookProperties;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.models.automation.AutomationLog;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.automation.AutomationLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Modern AutomationExecutorService with reactive WebClient and observability
 */
@Deprecated
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationExecutorService {

    private final EmailService emailService;
    private final SmsService smsService;
    private final EmailTemplateService emailTemplateService;
    private final AutomationLogRepository logRepository;
    private final WebClient webhookWebClient;
    private final WebhookProperties webhookProperties;
    private final MeterRegistry meterRegistry;

    /**
     * Execute workflow with modern observability and error handling
     */
    public boolean executeWorkflow(AutomationExecution execution) {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            boolean result = doExecuteWorkflow(execution);

            // Record metrics
            Counter.builder("automation.workflow.executions")
                    .description("Number of workflow executions")
                    .tag("status", result ? "success" : "failure")
                    .tag("workflow_id", execution.getWorkflow().getId().toString())
                    .register(meterRegistry)
                    .increment();

            sample.stop(Timer.builder("automation.workflow.execution.duration")
                    .description("Workflow execution duration")
                    .register(meterRegistry));

            return result;
        } catch (Exception e) {
            Counter.builder("automation.workflow.executions")
                    .description("Number of workflow executions")
                    .tag("status", "error")
                    .tag("workflow_id", execution.getWorkflow().getId().toString())
                    .register(meterRegistry)
                    .increment();

            sample.stop(Timer.builder("automation.workflow.execution.duration")
                    .description("Workflow execution duration")
                    .register(meterRegistry));
            throw e;
        }
    }

    private boolean doExecuteWorkflow(AutomationExecution execution) {
        AutomationWorkflow workflow = execution.getWorkflow();
        Customer customer = execution.getCustomer();

        log.info("Executing workflow '{}' for customer {} (execution: {})",
                workflow.getName(), customer.getName(), execution.getId());

        logExecution(execution, AutomationLog.LogLevel.INFO, "Starting workflow execution");

        try {
            // Handle modern delivery method approach
            if (workflow.getDeliveryMethod() != null) {
                return executeDeliveryMethod(workflow, customer, execution);
            }

            // Handle complex actions configuration
            Map<String, Object> actions = workflow.getActions();
            if (actions == null || actions.isEmpty()) {
                logExecution(execution, AutomationLog.LogLevel.WARN, "No actions defined for workflow");
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
                    logExecution(execution, AutomationLog.LogLevel.ERROR,
                            "Action '" + actionType + "' failed: " + e.getMessage());
                }
            }

            if (anyActionSucceeded) {
                logExecution(execution, AutomationLog.LogLevel.INFO, "Workflow execution completed successfully");
            } else {
                logExecution(execution, AutomationLog.LogLevel.ERROR, "All workflow actions failed");
            }

            return anyActionSucceeded;

        } catch (Exception e) {
            log.error("Error executing workflow for execution {}: {}", execution.getId(), e.getMessage(), e);
            logExecution(execution, AutomationLog.LogLevel.ERROR, "Workflow execution error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Execute simple delivery method (EMAIL, SMS, BOTH)
     */
    private boolean executeDeliveryMethod(AutomationWorkflow workflow, Customer customer, AutomationExecution execution) {
        return switch (workflow.getDeliveryMethod()) {
            case EMAIL -> executeEmailDelivery(workflow, customer, execution);
            case SMS -> executeSmsDelivery(workflow, customer, execution);
            case BOTH -> {
                boolean emailSuccess = executeEmailDelivery(workflow, customer, execution);
                yield emailSuccess || executeSmsDelivery(workflow, customer, execution);
            }
        };
    }

    private boolean executeEmailDelivery(AutomationWorkflow workflow, Customer customer, AutomationExecution execution) {
        try {
            boolean result;
            if (workflow.getEmailTemplate() != null) {
                // Use the existing sendFollowUpEmail method with the template
                EmailTemplate.TemplateType templateType = EmailTemplate.TemplateType.INITIAL_REQUEST; // Default
                result = emailService.sendFollowUpEmail(customer, templateType);
            } else {
                // Use default review request
                result = emailService.sendReviewRequestWithTemplate(customer);
            }

            // Record email metrics
            Counter.builder("automation.email.sent")
                    .description("Number of emails sent via automation")
                    .tag("success", String.valueOf(result))
                    .tag("workflow_id", workflow.getId().toString())
                    .register(meterRegistry)
                    .increment();

            return result;
        } catch (Exception e) {
            Counter.builder("automation.email.sent")
                    .description("Number of emails sent via automation")
                    .tag("success", "false")
                    .tag("workflow_id", workflow.getId().toString())
                    .register(meterRegistry)
                    .increment();

            logExecution(execution, AutomationLog.LogLevel.ERROR, "Email delivery failed: " + e.getMessage());
            return false;
        }
    }

    private boolean executeSmsDelivery(AutomationWorkflow workflow, Customer customer, AutomationExecution execution) {
        try {
            if (!customer.canReceiveSms()) {
                logExecution(execution, AutomationLog.LogLevel.WARN, "Customer cannot receive SMS");
                return false;
            }

            SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);

            // Record SMS metrics
            Counter.builder("automation.sms.sent")
                    .description("Number of SMS sent via automation")
                    .tag("success", String.valueOf(result.isSuccess()))
                    .tag("workflow_id", workflow.getId().toString())
                    .register(meterRegistry)
                    .increment();

            return result.isSuccess();
        } catch (Exception e) {
            Counter.builder("automation.sms.sent")
                    .description("Number of SMS sent via automation")
                    .tag("success", "false")
                    .tag("workflow_id", workflow.getId().toString())
                    .register(meterRegistry)
                    .increment();

            logExecution(execution, AutomationLog.LogLevel.ERROR, "SMS delivery failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Execute action with modern pattern matching
     */
    private boolean executeAction(String actionType, Object actionConfig, Customer customer, AutomationExecution execution) {
        if (!(actionConfig instanceof Map<?, ?> config)) {
            log.warn("Invalid action config for action '{}' - expected Map", actionType);
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) config;

        // Check if action is enabled
        if (Boolean.FALSE.equals(configMap.get("enabled"))) {
            log.debug("Action '{}' is disabled, skipping", actionType);
            return true;
        }

        return switch (actionType.toLowerCase()) {
            case "send_email", "email" -> executeEmailAction(configMap, customer, execution);
            case "send_sms", "sms" -> executeSmsAction(configMap, customer, execution);
            case "send_review_request", "review_request" -> executeReviewRequestAction(configMap, customer, execution);
            case "delay" -> executeDelayAction(configMap, customer, execution);
            case "webhook" -> executeWebhookAction(configMap, customer, execution);
            default -> {
                log.warn("Unknown action type: {}", actionType);
                yield false;
            }
        };
    }

    /**
     * Execute email action using existing service methods
     */
    private boolean executeEmailAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String templateType = (String) config.get("template_type");

            if (templateType != null) {
                EmailTemplate.TemplateType type = EmailTemplate.TemplateType.valueOf(templateType.toUpperCase());
                return emailService.sendFollowUpEmail(customer, type);
            } else {
                return emailService.sendReviewRequestWithTemplate(customer);
            }

        } catch (Exception e) {
            log.error("Failed to execute email action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute SMS action using existing service methods
     */
    private boolean executeSmsAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            SmsService.SmsEligibilityResult eligibility = smsService.getSmsEligibility(customer);
            if (!eligibility.isEligible()) {
                log.info("Customer {} not eligible for SMS: {}", customer.getId(), eligibility.getReason());
                return false;
            }

            String messageType = (String) config.getOrDefault("message_type", "review_request");

            return switch (messageType) {
                case "review_request" -> {
                    SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);
                    yield result.isSuccess();
                }
                case "follow_up" -> {
                    String followUpType = (String) config.getOrDefault("follow_up_type", "general");
                    SmsService.SmsResult followUpResult = smsService.sendFollowUpSms(customer, followUpType);
                    yield followUpResult.isSuccess();
                }
                case "thank_you" -> {
                    log.info("Thank you SMS requested for customer {} (not yet implemented)", customer.getId());
                    yield true;
                }
                default -> {
                    log.warn("Unknown SMS message type: {}", messageType);
                    yield false;
                }
            };

        } catch (Exception e) {
            log.error("Failed to execute SMS action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute review request action
     */
    private boolean executeReviewRequestAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        try {
            String deliveryMethod = (String) config.getOrDefault("delivery_method", "EMAIL");

            return switch (deliveryMethod.toUpperCase()) {
                case "EMAIL" -> {
                    // Use EmailService directly instead of ReviewRequestService
                    boolean result = emailService.sendReviewRequestWithTemplate(customer);
                    yield result;
                }
                case "SMS" -> {
                    // Use SmsService directly instead of ReviewRequestService
                    SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);
                    yield result.isSuccess();
                }
                case "BOTH" -> {
                    // Try email first, then SMS if email fails
                    try {
                        boolean emailResult = emailService.sendReviewRequestWithTemplate(customer);
                        yield emailResult;
                    } catch (Exception emailError) {
                        log.warn("Email failed, trying SMS for customer {}: {}",
                                customer.getId(), emailError.getMessage());
                        SmsService.SmsResult smsResult = smsService.sendReviewRequestSms(customer);
                        yield smsResult.isSuccess();
                    }
                }
                default -> {
                    log.warn("Unknown delivery method: {}", deliveryMethod);
                    yield false;
                }
            };

        } catch (Exception e) {
            log.error("Failed to execute review request action for customer {}: {}", customer.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Execute delay action
     */
    private boolean executeDelayAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        log.debug("Delay action processed for customer {}", customer.getId());
        return true;
    }

    /**
     * Modern reactive webhook implementation with retries and metrics
     */
    private boolean executeWebhookAction(Map<String, Object> config, Customer customer, AutomationExecution execution) {
        String webhookUrl = (String) config.get("webhook_url");
        if (webhookUrl == null) {
            log.warn("Webhook URL not configured for webhook action");
            return false;
        }

        String method = (String) config.getOrDefault("method", "POST");
        Map<String, Object> payload = buildWebhookPayload(customer, execution, config);

        Timer.Sample webhookTimer = Timer.start(meterRegistry);

        try {
            WebClient.RequestBodySpec request = webhookWebClient
                    .method(org.springframework.http.HttpMethod.valueOf(method.toUpperCase()))
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON);

            // Add custom headers
            if (config.containsKey("headers")) {
                @SuppressWarnings("unchecked")
                Map<String, String> headers = (Map<String, String>) config.get("headers");
                headers.forEach(request::header);
            }

            String response = request
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            Mono.error(new WebClientResponseException(
                                    clientResponse.statusCode().value(),
                                    "Webhook call failed",
                                    null, null, null)))
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(webhookProperties.maxRetries(),
                                    Duration.ofMillis(webhookProperties.retryDelayMs()))
                            .filter(throwable -> !(throwable instanceof WebClientResponseException wcre)
                                    || wcre.getStatusCode().is5xxServerError()))
                    .timeout(Duration.ofMillis(webhookProperties.readTimeout()))
                    .block();

            // Record successful webhook metrics
            Counter.builder("automation.webhook.calls")
                    .description("Number of webhook calls made")
                    .tag("success", "true")
                    .tag("method", method.toUpperCase())
                    .register(meterRegistry)
                    .increment();

            webhookTimer.stop(Timer.builder("automation.webhook.duration")
                    .description("Webhook call duration")
                    .register(meterRegistry));

            log.info("Successfully called webhook {} {} for customer {}", method, webhookUrl, customer.getId());
            logExecution(execution, AutomationLog.LogLevel.INFO,
                    "Webhook call successful: " + method + " " + webhookUrl);

            return true;

        } catch (Exception e) {
            // Record failed webhook metrics
            Counter.builder("automation.webhook.calls")
                    .description("Number of webhook calls made")
                    .tag("success", "false")
                    .tag("method", method.toUpperCase())
                    .register(meterRegistry)
                    .increment();

            webhookTimer.stop(Timer.builder("automation.webhook.duration")
                    .description("Webhook call duration")
                    .register(meterRegistry));

            log.error("Webhook call failed: {} {} - {}", method, webhookUrl, e.getMessage());
            logExecution(execution, AutomationLog.LogLevel.ERROR,
                    "Webhook call failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Build webhook payload
     */
    private Map<String, Object> buildWebhookPayload(Customer customer, AutomationExecution execution, Map<String, Object> config) {
        var payload = new HashMap<String, Object>();

        payload.put("event", execution.getTriggerEvent());
        payload.put("timestamp", execution.getCreatedAt().toString());
        payload.put("execution_id", execution.getId());
        payload.put("workflow_id", execution.getWorkflow().getId());

        payload.put("customer", Map.of(
                "id", customer.getId(),
                "name", customer.getName(),
                "email", customer.getEmail() != null ? customer.getEmail() : ""
        ));

        payload.put("business", Map.of(
                "id", customer.getBusiness().getId(),
                "name", customer.getBusiness().getName()
        ));

        if (execution.getTriggerData() != null) {
            payload.put("trigger_data", execution.getTriggerData());
        }

        if (config.containsKey("payload")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> customPayload = (Map<String, Object>) config.get("payload");
            payload.putAll(customPayload);
        }

        return payload;
    }

    /**
     * Async logging
     */
    private void logExecution(AutomationExecution execution, AutomationLog.LogLevel level, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                AutomationLog logEntry = AutomationLog.builder()
                        .executionId(execution.getId())
                        .workflowId(execution.getWorkflow().getId())
                        .logLevel(level)
                        .stepNumber(execution.getCurrentStep())
                        .message(message)
                        .build();

                logRepository.save(logEntry);
            } catch (Exception e) {
                log.error("Failed to save automation log: {}", e.getMessage());
            }
        });
    }
}