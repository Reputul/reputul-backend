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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AutomationTriggerService with smart workflow timing
 */
@Deprecated
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationTriggerService {

    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationSchedulerService schedulerService;
    private final CustomerRepository customerRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Smart trigger for customer creation - only triggers if work is actually done
     */
    @Transactional
    public void onCustomerCreated(Customer customer) {
        log.info("Processing automation triggers for new customer: {} (ID: {})",
                customer.getName(), customer.getId());

        try {
            // SMART LOGIC: Only trigger if customer is ready for automation
            if (!shouldTriggerAutomationForCustomer(customer)) {
                log.info("Skipping automation for customer {} - not ready (status: {}, service date: {}, ready flag: {})",
                        customer.getId(), customer.getStatus(), customer.getServiceDate(), customer.getReadyForAutomation());
                return;
            }

            // Prevent duplicate triggers
            if (Boolean.TRUE.equals(customer.getAutomationTriggered())) {
                log.info("Automation already triggered for customer {}", customer.getId());
                return;
            }

            triggerWorkflowsForCustomer(customer, "CUSTOMER_CREATED");

            // Mark automation as triggered
            customer.markAutomationTriggered();
            customerRepository.save(customer);

        } catch (Exception e) {
            log.error("Error processing customer created triggers for customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Trigger automation when service is explicitly completed
     */
    @Transactional
    public void onServiceCompleted(Customer customer, String serviceType) {
        log.info("Processing automation triggers for service completion: customer {} (service: {})",
                customer.getName(), serviceType);

        try {
            // Mark customer as service completed
            customer.markServiceCompleted();
            customerRepository.save(customer);

            // Trigger workflows if not already done
            if (!Boolean.TRUE.equals(customer.getAutomationTriggered())) {
                triggerWorkflowsForCustomer(customer, "SERVICE_COMPLETED", Map.of(
                        "service_type", serviceType,
                        "completion_date", customer.getServiceCompletedDate().toString(),
                        "triggered_by", "service_completion"
                ));

                customer.markAutomationTriggered();
                customerRepository.save(customer);
            }

        } catch (Exception e) {
            log.error("Error processing service completed triggers for customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Trigger automation when review request is completed
     */
    @Transactional
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
            Customer customer = customerRepository.findById(customerId).orElse(null);
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
     * Determine if customer is ready for automation
     */
    private boolean shouldTriggerAutomationForCustomer(Customer customer) {
        // Use the new smart logic in Customer entity
        return customer.isReadyForAutomation();
    }

    /**
     * Trigger workflows for a customer with specific event
     */
    private void triggerWorkflowsForCustomer(Customer customer, String triggerEvent) {
        triggerWorkflowsForCustomer(customer, triggerEvent, Map.of());
    }

    private void triggerWorkflowsForCustomer(Customer customer, String triggerEvent, Map<String, Object> additionalData) {
        try {
            AutomationWorkflow.TriggerType triggerType = AutomationWorkflow.TriggerType.valueOf(triggerEvent);

            List<AutomationWorkflow> workflows = workflowRepository
                    .findByOrganizationAndTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            customer.getUser().getOrganization(), triggerType);

            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("customer_name", customer.getName());
            triggerData.put("customer_email", customer.getEmail() != null ? customer.getEmail() : "");
            triggerData.put("service_type", customer.getServiceType() != null ? customer.getServiceType() : "");
            triggerData.put("business_id", customer.getBusiness().getId());
            triggerData.put("created_by", "automation_system");
            triggerData.putAll(additionalData);

            for (AutomationWorkflow workflow : workflows) {
                try {
                    if (shouldTriggerWorkflow(workflow, customer, triggerData)) {
                        schedulerService.scheduleFromTriggerConfig(
                                workflow, customer.getId(), triggerEvent, triggerData);

                        recordTriggerMetric(triggerEvent, workflow.getId(), true);
                        log.info("Triggered workflow '{}' for customer {}", workflow.getName(), customer.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to trigger workflow {} for customer {}: {}",
                            workflow.getId(), customer.getId(), e.getMessage());
                    recordTriggerMetric(triggerEvent, workflow.getId(), false);
                }
            }

        } catch (Exception e) {
            log.error("Error triggering workflows for customer {}: {}", customer.getId(), e.getMessage());
        }
    }

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