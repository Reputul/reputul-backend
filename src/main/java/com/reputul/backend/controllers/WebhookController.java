package com.reputul.backend.controllers;

import com.reputul.backend.models.Subscription;
import com.reputul.backend.services.StripeService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Handles Stripe webhook events for subscription lifecycle management
 *
 * IMPORTANT: This endpoint must be excluded from JWT authentication in security config
 * and should be accessible to Stripe's servers.
 *
 * Compatible with all Stripe Java library versions
 */
@RestController
@RequestMapping("/api/billing/webhook")
@Slf4j
public class WebhookController {

    private final StripeService stripeService;

    public WebhookController(StripeService stripeService) {
        this.stripeService = stripeService;
    }

    /**
     * Handle Stripe webhook events with proper security, error handling, and idempotency
     */
    @PostMapping("/stripe")
    public ResponseEntity<Map<String, Object>> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        if (payload == null || payload.trim().isEmpty()) {
            log.warn("Received empty webhook payload");
            return ResponseEntity.badRequest().body(Map.of("error", "Empty payload"));
        }

        if (sigHeader == null || sigHeader.trim().isEmpty()) {
            log.warn("Received webhook without Stripe-Signature header");
            return ResponseEntity.badRequest().body(Map.of("error", "Missing signature"));
        }

        log.info("Received Stripe webhook, payload size: {} bytes", payload.length());

        Event event;

        try {
            // Verify webhook signature to ensure it's from Stripe
            event = stripeService.constructWebhookEvent(payload, sigHeader);
            log.info("Successfully verified Stripe webhook event: {} (ID: {})",
                    event.getType(), event.getId());

        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Invalid signature"));
        } catch (Exception e) {
            log.error("Error verifying Stripe webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Webhook verification failed"));
        }

        // Check for duplicate events (basic idempotency)
        if (stripeService.isEventProcessed(event.getId())) {
            log.info("Event {} already processed, skipping", event.getId());
            return ResponseEntity.ok(Map.of(
                    "received", true,
                    "eventType", event.getType(),
                    "eventId", event.getId(),
                    "status", "already_processed"
            ));
        }

        // Process the event based on type
        try {
            // Compatible approach for accessing event data across Stripe library versions
            StripeObject stripeObject = getEventDataObject(event);

            if (stripeObject == null) {
                log.warn("Could not extract event data for event {} of type {}", event.getId(), event.getType());
                return ResponseEntity.badRequest().body(Map.of("error", "Could not extract event data"));
            }

            switch (event.getType()) {
                case "checkout.session.completed":
                    handleCheckoutSessionCompleted(event, stripeObject);
                    break;

                case "customer.subscription.updated":
                    handleSubscriptionUpdated(event, stripeObject);
                    break;

                case "customer.subscription.deleted":
                    handleSubscriptionDeleted(event, stripeObject);
                    break;

                case "invoice.payment_succeeded":
                    handlePaymentSucceeded(event, stripeObject);
                    break;

                case "invoice.payment_failed":
                    handlePaymentFailed(event, stripeObject);
                    break;

                case "customer.subscription.trial_will_end":
                    handleTrialWillEnd(event, stripeObject);
                    break;

                default:
                    log.debug("Unhandled webhook event type: {} (ID: {})", event.getType(), event.getId());
                    break;
            }

            // Mark event as processed
            stripeService.markEventAsProcessed(event.getId());

            log.info("Successfully processed webhook event: {} (ID: {})",
                    event.getType(), event.getId());

            return ResponseEntity.ok(Map.of(
                    "received", true,
                    "eventType", event.getType(),
                    "eventId", event.getId(),
                    "status", "processed"
            ));

        } catch (Exception e) {
            log.error("Error processing webhook event {} (ID: {}): {}",
                    event.getType(), event.getId(), e.getMessage(), e);

            // Return 500 so Stripe will retry the webhook
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "error", "Failed to process webhook event",
                            "eventType", event.getType(),
                            "eventId", event.getId()
                    ));
        }
    }

    /**
     * Extract StripeObject from Event in a way that works across library versions
     */
    private StripeObject getEventDataObject(Event event) {
        try {
            // Try the newer API first (v20.0+)
            if (event.getDataObjectDeserializer() != null) {
                return event.getDataObjectDeserializer().getObject().orElse(null);
            }
        } catch (Exception e) {
            log.debug("Newer event data API not available, trying legacy API");
        }

        try {
            // Try the legacy API (pre-v20.0)
            if (event.getData() != null) {
                return (StripeObject) event.getData().getObject();
            }
        } catch (Exception e) {
            log.debug("Legacy event data API not available");
        }

        // If neither works, log and return null
        log.warn("Could not extract event data using any known API for event {}", event.getId());
        return null;
    }

    /**
     * Handle successful checkout session completion
     * This is triggered when a user successfully pays for a subscription
     */
    private void handleCheckoutSessionCompleted(Event event, StripeObject stripeObject) {
        try {
            com.stripe.model.checkout.Session session =
                    (com.stripe.model.checkout.Session) stripeObject;

            log.info("Processing checkout session completed: {} for customer: {}",
                    session.getId(), session.getCustomer());

            // Validate required fields
            if (session.getSubscription() == null) {
                log.warn("Checkout session {} has no subscription, skipping", session.getId());
                return;
            }

            // Create/activate the local subscription record
            Subscription subscription = stripeService.processCheckoutCompleted(session);

            log.info("Successfully activated subscription {} for checkout session {}",
                    subscription.getId(), session.getId());

        } catch (StripeException e) {
            log.error("Stripe error processing checkout completion: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process checkout completion", e);
        } catch (Exception e) {
            log.error("Error processing checkout completion: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle subscription updates (plan changes, status changes, etc.)
     */
    private void handleSubscriptionUpdated(Event event, StripeObject stripeObject) throws StripeException {
        try {
            com.stripe.model.Subscription subscription =
                    (com.stripe.model.Subscription) stripeObject;

            log.info("Processing subscription updated: {} (status: {})",
                    subscription.getId(), subscription.getStatus());

            stripeService.updateSubscriptionFromStripe(subscription);

            log.info("Successfully updated local subscription for Stripe subscription {}",
                    subscription.getId());

        } catch (Exception e) {
            log.error("Error processing subscription update for {}: {}",
                    ((com.stripe.model.Subscription) stripeObject).getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle subscription cancellation
     */
    private void handleSubscriptionDeleted(Event event, StripeObject stripeObject) {
        try {
            com.stripe.model.Subscription subscription =
                    (com.stripe.model.Subscription) stripeObject;

            log.info("Processing subscription deleted: {}", subscription.getId());

            stripeService.cancelSubscriptionLocally(subscription.getId());

            log.info("Successfully cancelled local subscription for Stripe subscription {}",
                    subscription.getId());

        } catch (Exception e) {
            log.error("Error processing subscription deletion for {}: {}",
                    ((com.stripe.model.Subscription) stripeObject).getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle successful recurring payment
     */
    private void handlePaymentSucceeded(Event event, StripeObject stripeObject) throws StripeException {
        try {
            com.stripe.model.Invoice invoice = (com.stripe.model.Invoice) stripeObject;

            log.info("Processing payment succeeded for invoice: {} (customer: {})",
                    invoice.getId(), invoice.getCustomer());

            // Update subscription status if needed, handle usage billing
            if (invoice.getSubscription() != null) {
                stripeService.handleSuccessfulPayment(invoice);
            } else {
                log.debug("Invoice {} is not subscription-related, skipping", invoice.getId());
            }

        } catch (Exception e) {
            log.error("Error processing successful payment for invoice {}: {}",
                    ((com.stripe.model.Invoice) stripeObject).getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle failed payment (for dunning management)
     */
    private void handlePaymentFailed(Event event, StripeObject stripeObject) throws StripeException {
        try {
            com.stripe.model.Invoice invoice = (com.stripe.model.Invoice) stripeObject;

            log.warn("Processing payment failed for invoice: {} (customer: {})",
                    invoice.getId(), invoice.getCustomer());

            // Handle failed payment - may need to update subscription status
            if (invoice.getSubscription() != null) {
                stripeService.handleFailedPayment(invoice);
            } else {
                log.debug("Failed invoice {} is not subscription-related, skipping", invoice.getId());
            }

        } catch (Exception e) {
            log.error("Error processing failed payment for invoice {}: {}",
                    ((com.stripe.model.Invoice) stripeObject).getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Handle trial ending soon notification
     */
    private void handleTrialWillEnd(Event event, StripeObject stripeObject) {
        try {
            com.stripe.model.Subscription subscription =
                    (com.stripe.model.Subscription) stripeObject;

            log.info("Processing trial will end for subscription: {}", subscription.getId());

            stripeService.handleTrialWillEnd(subscription);

        } catch (Exception e) {
            log.error("Error processing trial will end for subscription {}: {}",
                    ((com.stripe.model.Subscription) stripeObject).getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Health check endpoint for webhook URL verification
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "stripe-webhooks",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Test endpoint for webhook setup verification
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> test() {
        return ResponseEntity.ok(Map.of(
                "status", "webhook endpoint is accessible",
                "service", "stripe-webhooks",
                "version", "1.0"
        ));
    }
}