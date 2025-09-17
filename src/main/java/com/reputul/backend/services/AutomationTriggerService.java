package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AutomationTriggerService
 *
 * Handles triggering automation workflows from business events
 * Integrates with existing services to automatically start workflows
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationTriggerService {

    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationSchedulerService schedulerService;
    private final MeterRegistry meterRegistry;
    private final CustomerRepository customerRepository;

    /**
     * Trigger automation when a customer is created
     */
    public void onCustomerCreated(Customer customer) {
        log.info("Processing automation triggers for new customer: {} (ID: {})",
                customer.getName(), customer.getId());

        try {
            List<AutomationWorkflow> workflows = workflowRepository
                    .findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            customer.getUser().getOrganization(),
                            AutomationWorkflow.TriggerType.CUSTOMER_CREATED);

            Map<String, Object> triggerData = Map.of(
                    "customer_name", customer.getName(),
                    "customer_email", customer.getEmail() != null ? customer.getEmail() : "",
                    "service_type", customer.getServiceType() != null ? customer.getServiceType() : "",
                    "business_id", customer.getBusiness().getId(),
                    "created_by", "customer_service"
            );

            for (AutomationWorkflow workflow : workflows) {
                try {
                    if (shouldTriggerWorkflow(workflow, customer, triggerData)) {
                        schedulerService.scheduleFromTriggerConfig(
                                workflow, customer.getId(), "CUSTOMER_CREATED", triggerData);

                        recordTriggerMetric("CUSTOMER_CREATED", workflow.getId(), true);
                        log.info("Triggered workflow '{}' for customer {}", workflow.getName(), customer.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to trigger workflow {} for customer {}: {}",
                            workflow.getId(), customer.getId(), e.getMessage());
                    recordTriggerMetric("CUSTOMER_CREATED", workflow.getId(), false);
                }
            }

        } catch (Exception e) {
            log.error("Error processing customer created triggers for customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Trigger automation when a service is completed
     */
    public void onServiceCompleted(Customer customer, String serviceType) {
        log.info("Processing automation triggers for service completion: customer {} (service: {})",
                customer.getName(), serviceType);

        try {
            List<AutomationWorkflow> workflows = workflowRepository
                    .findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            customer.getUser().getOrganization(),
                            AutomationWorkflow.TriggerType.SERVICE_COMPLETED);

            Map<String, Object> triggerData = Map.of(
                    "customer_name", customer.getName(),
                    "customer_email", customer.getEmail() != null ? customer.getEmail() : "",
                    "service_type", serviceType,
                    "business_id", customer.getBusiness().getId(),
                    "completion_date", java.time.OffsetDateTime.now().toString(),
                    "triggered_by", "service_completion"
            );

            for (AutomationWorkflow workflow : workflows) {
                try {
                    if (shouldTriggerWorkflow(workflow, customer, triggerData)) {
                        schedulerService.scheduleFromTriggerConfig(
                                workflow, customer.getId(), "SERVICE_COMPLETED", triggerData);

                        recordTriggerMetric("SERVICE_COMPLETED", workflow.getId(), true);
                        log.info("Triggered workflow '{}' for service completion: customer {}",
                                workflow.getName(), customer.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to trigger workflow {} for service completion {}: {}",
                            workflow.getId(), customer.getId(), e.getMessage());
                    recordTriggerMetric("SERVICE_COMPLETED", workflow.getId(), false);
                }
            }

        } catch (Exception e) {
            log.error("Error processing service completed triggers for customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Trigger automation when a review request is completed
     */
    public void onReviewRequestCompleted(ReviewRequest reviewRequest) {
        Customer customer = reviewRequest.getCustomer();
        log.info("Processing automation triggers for completed review: customer {} (request: {})",
                customer.getName(), reviewRequest.getId());

        try {
            List<AutomationWorkflow> workflows = workflowRepository
                    .findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            customer.getUser().getOrganization(),
                            AutomationWorkflow.TriggerType.REVIEW_COMPLETED);

            Map<String, Object> triggerData = Map.of(
                    "customer_name", customer.getName(),
                    "customer_email", customer.getEmail() != null ? customer.getEmail() : "",
                    "review_request_id", reviewRequest.getId(),
                    "delivery_method", reviewRequest.getDeliveryMethod().toString(),
                    "business_id", customer.getBusiness().getId(),
                    "completion_date", java.time.OffsetDateTime.now().toString(),
                    "triggered_by", "review_completion"
            );

            for (AutomationWorkflow workflow : workflows) {
                try {
                    if (shouldTriggerWorkflow(workflow, customer, triggerData)) {
                        schedulerService.scheduleFromTriggerConfig(
                                workflow, customer.getId(), "REVIEW_COMPLETED", triggerData);

                        recordTriggerMetric("REVIEW_COMPLETED", workflow.getId(), true);
                        log.info("Triggered workflow '{}' for review completion: customer {}",
                                workflow.getName(), customer.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to trigger workflow {} for review completion {}: {}",
                            workflow.getId(), customer.getId(), e.getMessage());
                    recordTriggerMetric("REVIEW_COMPLETED", workflow.getId(), false);
                }
            }

        } catch (Exception e) {
            log.error("Error processing review completed triggers for customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Handle webhook-triggered automation
     */
    public void onWebhookReceived(String webhookKey, Long customerId, Map<String, Object> webhookData) {
        log.info("Processing webhook automation trigger: {} for customer {}", webhookKey, customerId);

        try {
            Customer customer = getCustomerById(customerId);
            if (customer == null) {
                log.warn("Customer not found for webhook trigger: {}", customerId);
                return;
            }

            List<AutomationWorkflow> workflows = workflowRepository
                    .findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            customer.getUser().getOrganization(),
                            AutomationWorkflow.TriggerType.WEBHOOK);

            Map<String, Object> triggerData = new HashMap<>(webhookData);
            triggerData.put("webhook_key", webhookKey);
            triggerData.put("customer_name", customer.getName());
            triggerData.put("triggered_by", "webhook");

            for (AutomationWorkflow workflow : workflows) {
                try {
                    // Check if this workflow should respond to this webhook key
                    if (matchesWebhookKey(workflow, webhookKey) &&
                            shouldTriggerWorkflow(workflow, customer, triggerData)) {

                        schedulerService.scheduleFromTriggerConfig(
                                workflow, customer.getId(), "WEBHOOK_" + webhookKey, triggerData);

                        recordTriggerMetric("WEBHOOK", workflow.getId(), true);
                        log.info("Triggered workflow '{}' for webhook {}: customer {}",
                                workflow.getName(), webhookKey, customer.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to trigger workflow {} for webhook {}: {}",
                            workflow.getId(), webhookKey, e.getMessage());
                    recordTriggerMetric("WEBHOOK", workflow.getId(), false);
                }
            }

        } catch (Exception e) {
            log.error("Error processing webhook triggers for customer {}: {}", customerId, e.getMessage());
        }
    }

    // =========================
    // HELPER METHODS
    // =========================

    /**
     * Check if workflow should be triggered for this customer
     */
    private boolean shouldTriggerWorkflow(AutomationWorkflow workflow, Customer customer, Map<String, Object> triggerData) {
        Map<String, Object> conditions = workflow.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            return true; // No conditions = always trigger
        }

        try {
            // Check customer eligibility conditions
            if (conditions.containsKey("has_email") && Boolean.TRUE.equals(conditions.get("has_email"))) {
                if (customer.getEmail() == null || customer.getEmail().trim().isEmpty()) {
                    log.debug("Workflow {} skipped - customer {} has no email", workflow.getId(), customer.getId());
                    return false;
                }
            }

            if (conditions.containsKey("has_phone") && Boolean.TRUE.equals(conditions.get("has_phone"))) {
                if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
                    log.debug("Workflow {} skipped - customer {} has no phone", workflow.getId(), customer.getId());
                    return false;
                }
            }

            if (conditions.containsKey("service_types")) {
                @SuppressWarnings("unchecked")
                List<String> allowedServiceTypes = (List<String>) conditions.get("service_types");
                if (allowedServiceTypes != null && !allowedServiceTypes.isEmpty()) {
                    String customerServiceType = customer.getServiceType();
                    if (customerServiceType == null || !allowedServiceTypes.contains(customerServiceType)) {
                        log.debug("Workflow {} skipped - customer {} service type '{}' not in allowed list",
                                workflow.getId(), customer.getId(), customerServiceType);
                        return false;
                    }
                }
            }

            if (conditions.containsKey("max_executions_per_customer")) {
                Integer maxExecutions = (Integer) conditions.get("max_executions_per_customer");
                if (maxExecutions != null && maxExecutions > 0) {
                    // TODO: Check execution count for this customer/workflow combination
                    // For now, assume it's okay
                }
            }

            return true;

        } catch (Exception e) {
            log.error("Error evaluating workflow conditions for workflow {}: {}", workflow.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Check if workflow should respond to this webhook key
     */
    private boolean matchesWebhookKey(AutomationWorkflow workflow, String webhookKey) {
        Map<String, Object> triggerConfig = workflow.getTriggerConfig();
        if (triggerConfig == null) return false;

        Object webhookKeys = triggerConfig.get("webhook_keys");
        if (webhookKeys instanceof String) {
            return webhookKey.equals(webhookKeys);
        } else if (webhookKeys instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> keyList = (List<String>) webhookKeys;
            return keyList.contains(webhookKey);
        }

        return false;
    }

    /**
     * Get customer by ID (placeholder - you might need to inject CustomerRepository)
     */
    private Customer getCustomerById(Long customerId) {
        return customerRepository.findById(customerId)
                .orElse(null);
    }

    /**
     * Record trigger metrics
     */
    private void recordTriggerMetric(String triggerType, Long workflowId, boolean success) {
        Counter.builder("automation.triggers.fired")
                .description("Number of automation triggers fired")
                .tag("trigger_type", triggerType)
                .tag("workflow_id", workflowId.toString())
                .tag("success", String.valueOf(success))
                .register(meterRegistry)
                .increment();
    }
}