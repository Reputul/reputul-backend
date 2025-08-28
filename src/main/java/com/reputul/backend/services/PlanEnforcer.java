package com.reputul.backend.services;

import com.reputul.backend.config.PlanPolicy;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.repositories.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PlanEnforcer {

    private final PlanPolicy planPolicy;
    private final SubscriptionRepository subscriptionRepository;
    private final UsageService usageService;

    public PlanEnforcer(PlanPolicy planPolicy,
                        SubscriptionRepository subscriptionRepository,
                        UsageService usageService) {
        this.planPolicy = planPolicy;
        this.subscriptionRepository = subscriptionRepository;
        this.usageService = usageService;
    }

    /**
     * Check if customer creation is allowed for this business
     */
    public EnforcementResult canCreateCustomer(Business business) {
        try {
            Subscription subscription = getActiveSubscription(business);
            if (subscription == null) {
                return EnforcementResult.blocked("No active subscription. Please upgrade to create customers.",
                        "SUBSCRIPTION_REQUIRED", "/pricing");
            }

            if (subscription.isPastDue()) {
                return EnforcementResult.blocked("Payment is past due. Please update your payment method.",
                        "PAYMENT_REQUIRED", "/account/billing");
            }

            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);
            PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());

            if (!entitlement.allowsCustomerCreation(usage.getTotalCustomers())) {
                return EnforcementResult.blocked(
                        String.format("Customer limit reached (%d/%d). Upgrade your plan to add more customers.",
                                usage.getTotalCustomers(), entitlement.getMaxCustomers()),
                        "CUSTOMER_LIMIT_REACHED", "/pricing");
            }

            return EnforcementResult.allowed();

        } catch (Exception e) {
            log.error("Error checking customer creation entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Unable to verify plan limits. Please try again.",
                    "VERIFICATION_ERROR", "/account/billing");
        }
    }

    /**
     * Check if review request sending is allowed (daily limit + subscription status)
     */
    public EnforcementResult canSendReviewRequest(Business business) {
        try {
            Subscription subscription = getActiveSubscription(business);
            if (subscription == null) {
                return EnforcementResult.blocked("No active subscription. Please upgrade to send review requests.",
                        "SUBSCRIPTION_REQUIRED", "/pricing");
            }

            if (subscription.isPastDue()) {
                return EnforcementResult.blocked("Payment is past due. Please update your payment method to continue sending.",
                        "PAYMENT_REQUIRED", "/account/billing");
            }

            if (subscription.isCanceled()) {
                return EnforcementResult.blocked("Subscription canceled. Please resubscribe to send review requests.",
                        "SUBSCRIPTION_CANCELED", "/pricing");
            }

            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);
            PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());

            if (!entitlement.allowsDailyRequests(usage.getRequestsToday())) {
                return EnforcementResult.blocked(
                        String.format("Daily request limit reached (%d/%d). Upgrade your plan or wait until tomorrow.",
                                usage.getRequestsToday(), entitlement.getMaxRequestsPerDay()),
                        "DAILY_LIMIT_REACHED", "/pricing");
            }

            return EnforcementResult.allowed();

        } catch (Exception e) {
            log.error("Error checking review request entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Unable to verify plan limits. Please try again.",
                    "VERIFICATION_ERROR", "/account/billing");
        }
    }

    /**
     * Check if SMS sending is allowed (considers subscription + daily limits)
     */
    public EnforcementResult canSendSms(Business business) {
        EnforcementResult baseCheck = canSendReviewRequest(business);
        if (!baseCheck.isAllowed()) {
            return baseCheck;
        }

        try {
            Subscription subscription = getActiveSubscription(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);
            PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());

            // Check if we're over SMS limit (but still allow with overage billing)
            if (!entitlement.hasSmsAllowance(usage.getSmsSent())) {
                return EnforcementResult.allowedWithWarning(
                        String.format("SMS overage: This message will be billed separately. Current usage: %d (included: %d)",
                                usage.getSmsSent() + 1, entitlement.getIncludedSmsPerMonth()),
                        "SMS_OVERAGE", "/account/billing");
            }

            return EnforcementResult.allowed();

        } catch (Exception e) {
            log.error("Error checking SMS entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Unable to verify SMS limits. Please try again.",
                    "VERIFICATION_ERROR", "/account/billing");
        }
    }

    /**
     * Check if email sending is allowed
     */
    public EnforcementResult canSendEmail(Business business) {
        EnforcementResult baseCheck = canSendReviewRequest(business);
        if (!baseCheck.isAllowed()) {
            return baseCheck;
        }

        try {
            Subscription subscription = getActiveSubscription(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);
            PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());

            // Check email allowance (most plans have unlimited email)
            if (!entitlement.hasUnlimitedEmail() && !entitlement.hasEmailAllowance(usage.getEmailSent())) {
                return EnforcementResult.blocked(
                        String.format("Email limit reached (%d/%d). Upgrade your plan to send more emails.",
                                usage.getEmailSent(), entitlement.getIncludedEmailPerMonth()),
                        "EMAIL_LIMIT_REACHED", "/pricing");
            }

            return EnforcementResult.allowed();

        } catch (Exception e) {
            log.error("Error checking email entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Unable to verify email limits. Please try again.",
                    "VERIFICATION_ERROR", "/account/billing");
        }
    }

    /**
     * Get plan limits and current usage for display in UI
     */
    public Map<String, Object> getPlanStatus(Business business) {
        Map<String, Object> status = new HashMap<>();

        try {
            Subscription subscription = getActiveSubscription(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);

            status.put("hasActiveSubscription", subscription != null);

            if (subscription == null) {
                status.put("plan", "NONE");
                status.put("status", "INACTIVE");
                status.put("needsUpgrade", true);
                return status;
            }

            PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());

            status.put("plan", subscription.getPlan().name());
            status.put("status", subscription.getStatus().name());
            status.put("needsUpgrade", false);

            // Current usage
            status.put("usage", Map.of(
                    "customers", usage.getTotalCustomers(),
                    "maxCustomers", entitlement.getMaxCustomers(),
                    "requestsToday", usage.getRequestsToday(),
                    "maxRequestsPerDay", entitlement.getMaxRequestsPerDay(),
                    "smsSent", usage.getSmsSent(),
                    "smsIncluded", entitlement.getIncludedSmsPerMonth(),
                    "emailSent", usage.getEmailSent(),
                    "emailIncluded", entitlement.hasUnlimitedEmail() ? -1 : entitlement.getIncludedEmailPerMonth()
            ));

            // Limits and warnings
            status.put("limits", Map.of(
                    "customersAtLimit", usage.getTotalCustomers() >= entitlement.getMaxCustomers(),
                    "requestsAtDailyLimit", usage.getRequestsToday() >= entitlement.getMaxRequestsPerDay(),
                    "smsOverage", entitlement.getSmsOverage(usage.getSmsSent()),
                    "emailAtLimit", !entitlement.hasUnlimitedEmail() && usage.getEmailSent() >= entitlement.getIncludedEmailPerMonth()
            ));

            // Account status warnings
            status.put("warnings", Map.of(
                    "isPastDue", subscription.isPastDue(),
                    "isCanceled", subscription.isCanceled(),
                    "isTrialing", subscription.isTrialing()
            ));

        } catch (Exception e) {
            log.error("Error getting plan status for business {}: {}", business.getId(), e.getMessage(), e);
            status.put("error", "Unable to load plan status");
        }

        return status;
    }

    private Subscription getActiveSubscription(Business business) {
        return subscriptionRepository.findByBusinessId(business.getId())
                .filter(Subscription::isActive)
                .orElse(null);
    }

    /**
     * Result of an entitlement check
     */
    public static class EnforcementResult {
        private final boolean allowed;
        private final boolean hasWarning;
        private final String message;
        private final String errorCode;
        private final String upgradePath;

        private EnforcementResult(boolean allowed, boolean hasWarning, String message, String errorCode, String upgradePath) {
            this.allowed = allowed;
            this.hasWarning = hasWarning;
            this.message = message;
            this.errorCode = errorCode;
            this.upgradePath = upgradePath;
        }

        public static EnforcementResult allowed() {
            return new EnforcementResult(true, false, null, null, null);
        }

        public static EnforcementResult allowedWithWarning(String message, String errorCode, String upgradePath) {
            return new EnforcementResult(true, true, message, errorCode, upgradePath);
        }

        public static EnforcementResult blocked(String message, String errorCode, String upgradePath) {
            return new EnforcementResult(false, false, message, errorCode, upgradePath);
        }

        public boolean isAllowed() { return allowed; }
        public boolean hasWarning() { return hasWarning; }
        public String getMessage() { return message; }
        public String getErrorCode() { return errorCode; }
        public String getUpgradePath() { return upgradePath; }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new HashMap<>();
            result.put("allowed", allowed);
            result.put("hasWarning", hasWarning);
            if (message != null) result.put("message", message);
            if (errorCode != null) result.put("errorCode", errorCode);
            if (upgradePath != null) result.put("upgradePath", upgradePath);
            return result;
        }
    }
}