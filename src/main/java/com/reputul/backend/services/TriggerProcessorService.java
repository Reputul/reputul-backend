package com.reputul.backend.services;

import com.reputul.backend.models.automation.AutomationTrigger;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.Customer;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRequestRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Trigger Processing Service
 * Evaluates trigger conditions and determines when workflows should execute
 */
@Service
@Slf4j
public class TriggerProcessorService {

    private final CustomerRepository customerRepository;
    private final ReviewRequestRepository reviewRequestRepository;

    public TriggerProcessorService(CustomerRepository customerRepository,
                                   ReviewRequestRepository reviewRequestRepository) {
        this.customerRepository = customerRepository;
        this.reviewRequestRepository = reviewRequestRepository;
    }

    /**
     * Determine if a trigger should fire for a given customer and event
     */
    public boolean shouldFireTrigger(AutomationTrigger trigger, Long customerId, Map<String, Object> eventData) {
        log.debug("Evaluating trigger {} for customer {}", trigger.getId(), customerId);

        try {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            // Check organization match
            if (!trigger.getOrganization().getId().equals(customer.getBusiness().getUser().getOrganization().getId())) {
                return false;
            }

            // Evaluate trigger-specific conditions
            return evaluateTriggerConditions(trigger, customer, eventData);

        } catch (Exception e) {
            log.error("Error evaluating trigger {}: {}", trigger.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Evaluate workflow conditions for customer eligibility
     */
    public boolean evaluateWorkflowConditions(AutomationWorkflow workflow, Customer customer) {
        if (workflow.getConditions() == null || workflow.getConditions().isEmpty()) {
            return true;
        }

        Map<String, Object> conditions = workflow.getConditions();

        // Customer segment conditions
        if (conditions.containsKey("customer_segments")) {
            if (!evaluateCustomerSegmentCondition(customer, conditions)) {
                return false;
            }
        }

        // Business industry conditions
        if (conditions.containsKey("industries")) {
            if (!evaluateIndustryCondition(customer, conditions)) {
                return false;
            }
        }

        // Time-based conditions
        if (conditions.containsKey("execution_hours")) {
            if (!evaluateExecutionHoursCondition(conditions)) {
                return false;
            }
        }

        // Customer activity conditions
        if (conditions.containsKey("min_days_since_created")) {
            if (!evaluateCustomerAgeCondition(customer, conditions)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Process different types of event triggers
     */
    public void processEventTrigger(String eventType, Long customerId, Map<String, Object> eventData) {
        log.info("Processing {} event for customer {}", eventType, customerId);

        switch (eventType) {
            case "CUSTOMER_CREATED":
                processCustomerCreatedEvent(customerId, eventData);
                break;
            case "SERVICE_COMPLETED":
                processServiceCompletedEvent(customerId, eventData);
                break;
            case "REVIEW_COMPLETED":
                processReviewCompletedEvent(customerId, eventData);
                break;
            case "WEBHOOK_RECEIVED":
                processWebhookEvent(customerId, eventData);
                break;
            default:
                log.warn("Unknown event type: {}", eventType);
        }
    }

    // =========================
    // PRIVATE CONDITION EVALUATORS
    // =========================

    private boolean evaluateTriggerConditions(AutomationTrigger trigger, Customer customer, Map<String, Object> eventData) {
        Map<String, Object> conditions = trigger.getConditions();

        for (Map.Entry<String, Object> condition : conditions.entrySet()) {
            String conditionType = condition.getKey();
            Object conditionValue = condition.getValue();

            switch (conditionType) {
                case "business_id":
                    if (!evaluateBusinessIdCondition(customer, conditionValue)) {
                        return false;
                    }
                    break;
                case "customer_created_days_ago":
                    if (!evaluateCustomerCreatedDaysAgoCondition(customer, conditionValue)) {
                        return false;
                    }
                    break;
                case "no_recent_requests":
                    if (!evaluateNoRecentRequestsCondition(customer, conditionValue)) {
                        return false;
                    }
                    break;
                case "event_properties":
                    if (!evaluateEventPropertiesCondition(eventData, conditionValue)) {
                        return false;
                    }
                    break;
                default:
                    log.warn("Unknown condition type: {}", conditionType);
            }
        }

        return true;
    }

    private boolean evaluateBusinessIdCondition(Customer customer, Object conditionValue) {
        if (conditionValue instanceof Number) {
            Long businessId = ((Number) conditionValue).longValue();
            return customer.getBusiness().getId().equals(businessId);
        }
        return false;
    }

    private boolean evaluateCustomerCreatedDaysAgoCondition(Customer customer, Object conditionValue) {
        if (conditionValue instanceof Number) {
            int daysAgo = ((Number) conditionValue).intValue();
            OffsetDateTime cutoff = OffsetDateTime.now().minusDays(daysAgo);
            return customer.getCreatedAt().isBefore(cutoff);
        }
        return false;
    }

    private boolean evaluateNoRecentRequestsCondition(Customer customer, Object conditionValue) {
        if (conditionValue instanceof Number) {
            int days = ((Number) conditionValue).intValue();
            OffsetDateTime since = OffsetDateTime.now().minusDays(days);

            // Check if customer has any review requests in the specified period
            return !reviewRequestRepository.hasRecentRequests(customer.getId(), since);
        }
        return false;
    }

    private boolean evaluateEventPropertiesCondition(Map<String, Object> eventData, Object conditionValue) {
        if (conditionValue instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> requiredProperties = (Map<String, Object>) conditionValue;

            for (Map.Entry<String, Object> property : requiredProperties.entrySet()) {
                String key = property.getKey();
                Object expectedValue = property.getValue();

                if (!eventData.containsKey(key) || !eventData.get(key).equals(expectedValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean evaluateCustomerSegmentCondition(Customer customer, Map<String, Object> conditions) {
        // This could evaluate customer segments based on various criteria
        // For now, return true as placeholder
        return true;
    }

    private boolean evaluateIndustryCondition(Customer customer, Map<String, Object> conditions) {
        @SuppressWarnings("unchecked")
        java.util.List<String> allowedIndustries = (java.util.List<String>) conditions.get("industries");
        return allowedIndustries.contains(customer.getBusiness().getIndustry());
    }

    private boolean evaluateExecutionHoursCondition(Map<String, Object> conditions) {
        @SuppressWarnings("unchecked")
        Map<String, Object> executionHours = (Map<String, Object>) conditions.get("execution_hours");

        int startHour = ((Number) executionHours.get("start")).intValue();
        int endHour = ((Number) executionHours.get("end")).intValue();

        int currentHour = OffsetDateTime.now().getHour();

        if (startHour <= endHour) {
            return currentHour >= startHour && currentHour <= endHour;
        } else {
            // Handle overnight hours (e.g., 22:00 to 08:00)
            return currentHour >= startHour || currentHour <= endHour;
        }
    }

    private boolean evaluateCustomerAgeCondition(Customer customer, Map<String, Object> conditions) {
        int minDays = ((Number) conditions.get("min_days_since_created")).intValue();
        long daysSinceCreated = ChronoUnit.DAYS.between(customer.getCreatedAt(), OffsetDateTime.now());
        return daysSinceCreated >= minDays;
    }

    // =========================
    // EVENT PROCESSORS
    // =========================

    private void processCustomerCreatedEvent(Long customerId, Map<String, Object> eventData) {
        // This could trigger welcome sequences or onboarding workflows
        log.info("Processing customer created event for customer {}", customerId);
    }

    private void processServiceCompletedEvent(Long customerId, Map<String, Object> eventData) {
        // This is the most common trigger - service completion leading to review request
        log.info("Processing service completed event for customer {}", customerId);
    }

    private void processReviewCompletedEvent(Long customerId, Map<String, Object> eventData) {
        // This could trigger thank you messages or follow-up sequences
        log.info("Processing review completed event for customer {}", customerId);
    }

    private void processWebhookEvent(Long customerId, Map<String, Object> eventData) {
        // Process external webhook events (e.g., from CRM systems, Zapier, etc.)
        String webhookSource = (String) eventData.get("source");
        log.info("Processing webhook event from {} for customer {}", webhookSource, customerId);
    }
}