package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.PlanEnforcer;
import com.reputul.backend.services.StripeService;
import com.reputul.backend.services.UsageService;
import com.stripe.exception.StripeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for all billing-related operations including:
 * - Checkout session creation
 * - Billing portal access
 * - Subscription information retrieval
 * - Plan entitlement checking
 */
@RestController
@RequestMapping("/api/billing")
@Slf4j
public class BillingController {

    private final StripeService stripeService;
    private final UsageService usageService;
    private final PlanEnforcer planEnforcer;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;

    public BillingController(StripeService stripeService,
                             UsageService usageService,
                             PlanEnforcer planEnforcer,
                             UserRepository userRepository,
                             BusinessRepository businessRepository) {
        this.stripeService = stripeService;
        this.usageService = usageService;
        this.planEnforcer = planEnforcer;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
    }

    /**
     * Create a checkout session for subscribing to a plan
     */
    @PostMapping("/checkout-session")
    public ResponseEntity<Map<String, Object>> createCheckoutSession(
            @Valid @RequestBody CheckoutRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            log.info("Creating checkout session for user {}, plan: {}, promo: {}",
                    user.getId(), request.getPlan(), request.getPromoCode());

            // Validate plan
            Subscription.PlanType planType;
            try {
                planType = Subscription.PlanType.valueOf(request.getPlan().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid plan: " + request.getPlan(),
                        "validPlans", new String[]{"SOLO", "PRO", "GROWTH"}
                ));
            }

            // Check if user already has an active subscription
            try {
                Map<String, Object> existingSubscription = stripeService.getSubscriptionSummary(user);
                if (Boolean.TRUE.equals(existingSubscription.get("hasSubscription"))) {
                    String currentStatus = (String) existingSubscription.get("status");
                    if ("ACTIVE".equals(currentStatus) || "TRIALING".equals(currentStatus)) {
                        return ResponseEntity.badRequest().body(Map.of(
                                "error", "User already has an active subscription",
                                "currentPlan", existingSubscription.get("plan"),
                                "currentStatus", currentStatus,
                                "suggestion", "Use billing portal to manage existing subscription"
                        ));
                    }
                }
            } catch (Exception e) {
                // Log but don't block checkout if subscription check fails
                log.warn("Failed to check existing subscription for user {}: {}", user.getId(), e.getMessage());
            }

            // Validate promo code if provided
            if (request.getPromoCode() != null && !request.getPromoCode().trim().isEmpty()) {
                if (!stripeService.isValidPromoCode(request.getPromoCode())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Invalid promo code: " + request.getPromoCode()
                    ));
                }
            }

            String checkoutUrl = stripeService.createCheckoutSession(user, planType,
                    request.getPromoCode() != null ? request.getPromoCode().trim() : null);

            return ResponseEntity.ok(Map.of(
                    "url", checkoutUrl,
                    "plan", planType.name(),
                    "promoCode", request.getPromoCode() != null ? request.getPromoCode() : "",
                    "message", "Checkout session created successfully"
            ));

        } catch (StripeException e) {
            log.error("Stripe error creating checkout session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create checkout session: " + e.getCode(),
                    "message", e.getMessage(),
                    "type", "stripe_error"
            ));
        } catch (IllegalArgumentException e) {
            log.error("Invalid argument for checkout session: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "type", "validation_error"
            ));
        } catch (Exception e) {
            log.error("Error creating checkout session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Internal server error",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Create a billing portal session for managing subscription
     */
    @PostMapping("/portal-session")
    public ResponseEntity<Map<String, Object>> createPortalSession(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            log.info("Creating billing portal session for user {}", user.getId());

            // Check if user has a subscription
            Map<String, Object> subscriptionSummary = stripeService.getSubscriptionSummary(user);
            if (!Boolean.TRUE.equals(subscriptionSummary.get("hasSubscription"))) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No subscription found",
                        "message", "User must have a subscription to access billing portal",
                        "suggestion", "Create a subscription first"
                ));
            }

            String portalUrl = stripeService.createBillingPortalSession(user);

            return ResponseEntity.ok(Map.of(
                    "url", portalUrl,
                    "message", "Billing portal session created successfully"
            ));

        } catch (StripeException e) {
            log.error("Stripe error creating portal session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create portal session: " + e.getCode(),
                    "message", e.getMessage(),
                    "type", "stripe_error"
            ));
        } catch (Exception e) {
            log.error("Error creating portal session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Internal server error",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Get comprehensive subscription and usage information
     */
    @GetMapping("/subscription")
    public ResponseEntity<Map<String, Object>> getSubscriptionInfo(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                    .orElse(null);

            Map<String, Object> response = new HashMap<>();

            // Get subscription summary
            Map<String, Object> subscriptionSummary = stripeService.getSubscriptionSummary(user);
            response.put("subscription", subscriptionSummary);

            // Get usage information if there's a business
            if (primaryBusiness != null) {
                Map<String, Object> usageSummary = usageService.getUsageSummaryForApi(primaryBusiness);
                response.put("usage", usageSummary);

                // Get plan status and limits
                Map<String, Object> planStatus = planEnforcer.getPlanStatus(primaryBusiness);
                response.put("planStatus", planStatus);

                // Get usage history (last 6 months)
                response.put("usageHistory", usageService.getUsageHistory(primaryBusiness, 6));
            } else {
                // User has no business yet
                response.put("usage", Map.of(
                        "hasBusiness", false,
                        "message", "No business found - create a business to view usage"
                ));
            }

            // Add helpful metadata
            response.put("timestamp", System.currentTimeMillis());
            response.put("userId", user.getId());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting subscription info for user: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to load subscription information",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Get plan entitlements and current usage for a specific business
     */
    @GetMapping("/business/{businessId}/status")
    public ResponseEntity<Map<String, Object>> getBusinessBillingStatus(
            @PathVariable Long businessId,
            Authentication authentication) {

        try {
            User user = getCurrentUser(authentication);

            Business business = businessRepository.findByIdAndUserId(businessId, user.getId())
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> response = new HashMap<>();

            // Get plan status
            Map<String, Object> planStatus = planEnforcer.getPlanStatus(business);
            response.put("planStatus", planStatus);

            // Get usage summary
            Map<String, Object> usageSummary = usageService.getUsageSummaryForApi(business);
            response.put("usage", usageSummary);

            // Add business info
            response.put("business", Map.of(
                    "id", business.getId(),
                    "name", business.getName(),
                    "industry", business.getIndustry()
            ));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting business billing status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to load business billing status",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Check if user can perform a specific action (plan enforcement)
     */
    @PostMapping("/check-entitlement")
    public ResponseEntity<Map<String, Object>> checkEntitlement(
            @Valid @RequestBody EntitlementCheckRequest request,
            Authentication authentication) {

        try {
            User user = getCurrentUser(authentication);

            Business business = businessRepository.findByIdAndUserId(request.getBusinessId(), user.getId())
                    .orElse(null);

            if (business == null) {
                return ResponseEntity.notFound().build();
            }

            PlanEnforcer.EnforcementResult result = switch (request.getAction().toUpperCase()) {
                case "CREATE_CUSTOMER" -> planEnforcer.canCreateCustomer(business);
                case "SEND_SMS" -> planEnforcer.canSendSms(business);
                case "SEND_EMAIL" -> planEnforcer.canSendEmail(business);
                case "SEND_REQUEST" -> planEnforcer.canSendReviewRequest(business);
                default -> PlanEnforcer.EnforcementResult.blocked("Unknown action: " + request.getAction(),
                        "INVALID_ACTION", "/account/billing");
            };

            return ResponseEntity.ok(result.toMap());

        } catch (Exception e) {
            log.error("Error checking entitlement: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "allowed", false,
                    "message", "Error checking plan limits",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Get available plans with pricing information
     */
    @GetMapping("/plans")
    public ResponseEntity<Map<String, Object>> getPlans() {
        try {
            Map<String, Object> plans = new HashMap<>();

            for (Subscription.PlanType plan : Subscription.PlanType.values()) {
                plans.put(plan.name(), Map.of(
                        "name", plan.getDisplayName(),
                        "price", plan.getPriceDisplay(),
                        "maxCustomers", plan.getMaxCustomers(),
                        "includedSms", plan.getIncludedSmsPerMonth()
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "plans", plans,
                    "currency", "USD",
                    "billing", "monthly",
                    "smsOveragePrice", "$0.05 per message"
            ));

        } catch (Exception e) {
            log.error("Error getting plans: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to load plans",
                    "type", "server_error"
            ));
        }
    }

    /**
     * Health check for billing service
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "billing",
                "timestamp", System.currentTimeMillis()
        ));
    }

    // Helper method to get current user
    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    // Request DTOs with validation
    public static class CheckoutRequest {
        @NotBlank(message = "Plan is required")
        private String plan;

        private String promoCode;

        public String getPlan() { return plan; }
        public void setPlan(String plan) { this.plan = plan; }
        public String getPromoCode() { return promoCode; }
        public void setPromoCode(String promoCode) { this.promoCode = promoCode; }
    }

    public static class EntitlementCheckRequest {
        @NotNull(message = "Business ID is required")
        private Long businessId;

        @NotBlank(message = "Action is required")
        private String action;

        public Long getBusinessId() { return businessId; }
        public void setBusinessId(Long businessId) { this.businessId = businessId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}