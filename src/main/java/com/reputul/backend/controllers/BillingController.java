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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

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
            @RequestBody CheckoutRequest request,
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

            // Validate promo code if provided
            if (request.getPromoCode() != null && !request.getPromoCode().trim().isEmpty()) {
                if (!stripeService.isValidPromoCode(request.getPromoCode())) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Invalid promo code: " + request.getPromoCode()
                    ));
                }
            }

            String checkoutUrl = stripeService.createCheckoutSession(user, planType, request.getPromoCode());

            return ResponseEntity.ok(Map.of(
                    "url", checkoutUrl,
                    "plan", planType.name(),
                    "promoCode", request.getPromoCode() != null ? request.getPromoCode() : ""
            ));

        } catch (StripeException e) {
            log.error("Stripe error creating checkout session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create checkout session: " + e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error creating checkout session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Internal server error"
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

            String portalUrl = stripeService.createBillingPortalSession(user);

            return ResponseEntity.ok(Map.of("url", portalUrl));

        } catch (StripeException e) {
            log.error("Stripe error creating portal session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to create portal session: " + e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Error creating portal session: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Internal server error"
            ));
        }
    }

    /**
     * Get subscription and usage information
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
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting subscription info for user: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to load subscription information"
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

            Map<String, Object> status = planEnforcer.getPlanStatus(business);
            Map<String, Object> usage = usageService.getUsageSummaryForApi(business);

            return ResponseEntity.ok(Map.of(
                    "planStatus", status,
                    "usage", usage,
                    "businessId", businessId,
                    "businessName", business.getName()
            ));

        } catch (Exception e) {
            log.error("Error getting business billing status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to load billing status"
            ));
        }
    }

    /**
     * Validate a promo code
     */
    @PostMapping("/validate-promo")
    public ResponseEntity<Map<String, Object>> validatePromoCode(@RequestBody Map<String, String> request) {
        try {
            String promoCode = request.get("promoCode");

            if (promoCode == null || promoCode.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "message", "Promo code is required"
                ));
            }

            boolean isValid = stripeService.isValidPromoCode(promoCode);

            if (isValid) {
                return ResponseEntity.ok(Map.of(
                        "valid", true,
                        "message", "Valid promo code! You'll get 3 months free, then 50% off forever.",
                        "discount", Map.of(
                                "phase1", "100% off for 3 months",
                                "phase2", "50% off thereafter",
                                "description", "3 months free, then 50% off forever"
                        )
                ));
            } else {
                return ResponseEntity.ok(Map.of(
                        "valid", false,
                        "message", "Invalid promo code"
                ));
            }

        } catch (Exception e) {
            log.error("Error validating promo code: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "message", "Error validating promo code"
            ));
        }
    }

    /**
     * Check entitlements before performing an action
     */
    @PostMapping("/check-entitlement")
    public ResponseEntity<Map<String, Object>> checkEntitlement(
            @RequestBody EntitlementCheckRequest request,
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
                    "message", "Error checking plan limits"
            ));
        }
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // Request DTOs
    public static class CheckoutRequest {
        private String plan;
        private String promoCode;

        public String getPlan() { return plan; }
        public void setPlan(String plan) { this.plan = plan; }
        public String getPromoCode() { return promoCode; }
        public void setPromoCode(String promoCode) { this.promoCode = promoCode; }
    }

    public static class EntitlementCheckRequest {
        private Long businessId;
        private String action;

        public Long getBusinessId() { return businessId; }
        public void setBusinessId(Long businessId) { this.businessId = businessId; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}