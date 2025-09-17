package com.reputul.backend.services;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.services.integration.AutomationEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Lazy;

/**
 * AutomationIntegration Service
 * Handles automated workflows triggered by customer and review request events
 * Integrates with the new AutomationEventService for comprehensive automation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationIntegration {

    private final @Lazy ReviewRequestService reviewRequestService;
    private final AutomationEventService automationEventService;

    /**
     * Triggered when a new customer is created
     * Publishes customer created event for automation workflows
     */
    public void onCustomerCreated(Customer customer) {
        log.info("Customer created event triggered for: {} (ID: {})", customer.getName(), customer.getId());

        // Publish automation event
        automationEventService.publishCustomerCreatedEvent(customer.getId());

        // Log automation eligibility
        log.debug("Customer {} is eligible for automated workflows: SMS={}, Email={}",
                customer.getName(),
                customer.canReceiveSms(),
                customer.getEmail() != null);
    }

    /**
     * Triggered when a review request is completed (customer submitted feedback)
     * Publishes review completed event for automation workflows
     */
    public void onReviewRequestCompleted(ReviewRequest reviewRequest) {
        log.info("Review request completed event triggered for customer: {} (Request ID: {})",
                reviewRequest.getCustomer().getName(),
                reviewRequest.getId());

        // Publish automation event
        automationEventService.publishReviewCompletedEvent(
                reviewRequest.getCustomer().getId(),
                reviewRequest.getId());

        // Log completion details
        log.debug("Review request completed - Customer: {}, Method: {}, Business: {}",
                reviewRequest.getCustomer().getName(),
                reviewRequest.getDeliveryMethod(),
                reviewRequest.getBusiness().getName());
    }

    /**
     * Triggered when a service is completed
     * Can be called manually or via integrations
     */
    public void onServiceCompleted(Customer customer, String serviceType) {
        log.info("Service completed event triggered for customer: {} (Service: {})",
                customer.getName(), serviceType);

        // Publish automation event
        automationEventService.publishServiceCompletedEvent(customer.getId(), serviceType);
    }

    /**
     * Check if customer should receive automated follow-ups
     */
    public boolean shouldTriggerFollowUp(Customer customer) {
        // Don't send follow-ups if customer already responded
        return !reviewRequestService.hasCustomerResponded(customer);
    }

    /**
     * Schedule automated review request (placeholder for future implementation)
     * This will be enhanced when job scheduling is implemented
     */
    public void scheduleAutomatedReviewRequest(Customer customer, int delayDays) {
        log.info("Scheduling automated review request for {} in {} days",
                customer.getName(), delayDays);

        // TODO: Implement actual scheduling logic
        // This could integrate with a job scheduler like Quartz or Spring Scheduler
        // For now, just publish an event that automation workflows can pick up

        // Could also integrate with the workflow execution system
        log.debug("Automated scheduling will be handled by workflow execution engine");
    }

    /**
     * Process webhook automation trigger
     */
    public void onWebhookReceived(String webhookKey, java.util.Map<String, Object> webhookData) {
        log.info("Webhook automation trigger received: {}", webhookKey);

        if (webhookData.containsKey("customer_id")) {
            Long customerId = ((Number) webhookData.get("customer_id")).longValue();
            automationEventService.publishWebhookEvent(customerId, webhookKey, webhookData);
        } else {
            log.warn("Webhook received without customer_id: {}", webhookKey);
        }
    }
}