package com.reputul.backend.services;

import com.reputul.backend.config.PlanPolicy;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.repositories.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Service for enforcing plan limits and entitlements across the platform
 * Integrates with subscription status to determine what users can do
 */
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
     * Check if business can create a new customer
     */
    public EnforcementResult canCreateCustomer(Business business) {
        try {
            PlanPolicy.PlanEntitlement entitlement = getEffectiveEntitlement(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);

            if (usage.totalCustomers >= entitlement.getMaxCustomers()) {
                return EnforcementResult.blocked(
                        String.format("Customer limit reached (%d/%d). Upgrade your plan to add more customers.",
                                usage.totalCustomers, entitlement.getMaxCustomers()),
                        "CUSTOMER_LIMIT_EXCEEDED",
                        "/account/billing"
                );
            }

            return EnforcementResult.allowed("Customer creation allowed");

        } catch (Exception e) {
            log.error("Error checking customer creation entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Error checking plan limits", "SYSTEM_ERROR", "/account/billing");
        }
    }

    /**
     * Check if business can send SMS
     */
    public EnforcementResult canSendSms(Business business) {
        try {
            // Check if business has active subscription
            Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());
            if (subscriptionOpt.isEmpty()) {
                return EnforcementResult.blocked(
                        "Active subscription required to send SMS messages",
                        "NO_SUBSCRIPTION",
                        "/account/billing"
                );
            }

            PlanPolicy.PlanEntitlement entitlement = getEffectiveEntitlement(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);

            // Check if over included SMS limit (but allow overage billing)
            if (usage.smsSent >= entitlement.getIncludedSmsPerMonth()) {
                // SMS overages are allowed but will be billed
                return EnforcementResult.allowedWithWarning(
                        String.format("SMS will incur overage charges. Used: %d, Included: %d",
                                usage.smsSent, entitlement.getIncludedSmsPerMonth()),
                        "SMS_OVERAGE_WARNING"
                );
            }

            return EnforcementResult.allowed("SMS sending allowed");

        } catch (Exception e) {
            log.error("Error checking SMS entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Error checking SMS limits", "SYSTEM_ERROR", "/account/billing");
        }
    }

    /**
     * Check if business can send email
     */
    public EnforcementResult canSendEmail(Business business) {
        try {
            PlanPolicy.PlanEntitlement entitlement = getEffectiveEntitlement(business);

            // All current plans have unlimited email
            if (entitlement.getIncludedEmailPerMonth() == -1) {
                return EnforcementResult.allowed("Email sending allowed (unlimited)");
            }

            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);

            if (usage.emailSent >= entitlement.getIncludedEmailPerMonth()) {
                return EnforcementResult.blocked(
                        String.format("Email limit reached (%d/%d). Upgrade your plan for more emails.",
                                usage.emailSent, entitlement.getIncludedEmailPerMonth()),
                        "EMAIL_LIMIT_EXCEEDED",
                        "/account/billing"
                );
            }

            return EnforcementResult.allowed("Email sending allowed");

        } catch (Exception e) {
            log.error("Error checking email entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Error checking email limits", "SYSTEM_ERROR", "/account/billing");
        }
    }

    /**
     * Check if business can send review request (daily rate limit)
     */
    public EnforcementResult canSendReviewRequest(Business business) {
        try {
            PlanPolicy.PlanEntitlement entitlement = getEffectiveEntitlement(business);

            // Check daily rate limit
            if (usageService.isOverDailyRequestLimit(business)) {
                return EnforcementResult.blocked(
                        String.format("Daily review request limit reached (%d). Try again tomorrow or upgrade your plan.",
                                entitlement.getMaxRequestsPerDay()),
                        "DAILY_REQUEST_LIMIT_EXCEEDED",
                        "/account/billing"
                );
            }

            return EnforcementResult.allowed("Review request allowed");

        } catch (Exception e) {
            log.error("Error checking review request entitlement for business {}: {}", business.getId(), e.getMessage(), e);
            return EnforcementResult.blocked("Error checking request limits", "SYSTEM_ERROR", "/account/billing");
        }
    }

    /**
     * Get comprehensive plan status for a business
     */
    public Map<String, Object> getPlanStatus(Business business) {
        Map<String, Object> status = new HashMap<>();

        try {
            Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());
            PlanPolicy.PlanEntitlement entitlement = getEffectiveEntitlement(business);
            UsageService.UsageStats usage = usageService.getCurrentPeriodUsage(business);

            // Subscription info
            if (subscriptionOpt.isPresent()) {
                Subscription subscription = subscriptionOpt.get();
                status.put("hasActiveSubscription", true);
                status.put("plan", subscription.getPlan().name());
                status.put("planDisplayName", subscription.getPlan().getDisplayName());
                status.put("status", subscription.getStatus().name());
                status.put("statusDescription", subscription.getStatusDescription());

                if (subscription.isTrialing()) {
                    status.put("trialDaysRemaining", subscription.getTrialDaysRemaining());
                }

                if (subscription.needsAttention()) {
                    status.put("needsAttention", true);
                    status.put("attentionReason", getAttentionReason(subscription));
                }
            } else {
                status.put("hasActiveSubscription", false);
                status.put("plan", "FREE");
                status.put("planDisplayName", "Free Tier");
                status.put("status", "INACTIVE");
            }

            // Entitlements
            Map<String, Object> entitlements = new HashMap<>();
            entitlements.put("maxCustomers", entitlement.getMaxCustomers());
            entitlements.put("maxRequestsPerDay", entitlement.getMaxRequestsPerDay());
            entitlements.put("includedSmsPerMonth", entitlement.getIncludedSmsPerMonth());
            entitlements.put("includedEmailPerMonth", entitlement.getIncludedEmailPerMonth());
            status.put("entitlements", entitlements);

            // Current usage
            Map<String, Object> currentUsage = new HashMap<>();
            currentUsage.put("totalCustomers", usage.totalCustomers);
            currentUsage.put("smsSent", usage.smsSent);
            currentUsage.put("emailSent", usage.emailSent);
            currentUsage.put("requestsToday", getTodaysRequestCount(business));
            status.put("usage", currentUsage);

            // Usage percentages and warnings
            Map<String, Object> usagePercentages = new HashMap<>();
            usagePercentages.put("customers", calculateUsagePercent(usage.totalCustomers, entitlement.getMaxCustomers()));
            usagePercentages.put("sms", calculateUsagePercent(usage.smsSent, entitlement.getIncludedSmsPerMonth()));
            usagePercentages.put("dailyRequests", calculateUsagePercent(getTodaysRequestCount(business), entitlement.getMaxRequestsPerDay()));
            status.put("usagePercentages", usagePercentages);

            // Warnings and limits
            status.put("warnings", generateWarnings(business, entitlement, usage));
            status.put("limits", generateLimitStatus(business, entitlement, usage));

        } catch (Exception e) {
            log.error("Error getting plan status for business {}: {}", business.getId(), e.getMessage(), e);
            status.put("error", "Failed to load plan status");
        }

        return status;
    }

    /**
     * Get effective entitlement for a business (considering subscription status)
     */
    private PlanPolicy.PlanEntitlement getEffectiveEntitlement(Business business) {
        Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());

        if (subscriptionOpt.isEmpty()) {
            // No active subscription = free tier with very limited access
            return PlanPolicy.PlanEntitlement.builder()
                    .maxCustomers(10)
                    .maxRequestsPerDay(5)
                    .includedSmsPerMonth(0) // No SMS on free tier
                    .includedEmailPerMonth(50)
                    .build();
        }

        Subscription subscription = subscriptionOpt.get();
        return planPolicy.getEntitlement(subscription.getPlan());
    }

    private int getTodaysRequestCount(Business business) {
        java.time.OffsetDateTime todayStart = java.time.OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0);
        java.time.OffsetDateTime todayEnd = todayStart.plusDays(1);

        return usageService.getCurrentPeriodUsage(business).reviewRequestsSent; // This should be filtered by today
    }

    private int calculateUsagePercent(long used, int limit) {
        if (limit <= 0 || limit == -1) return 0; // Unlimited
        return (int) Math.min(100, (used * 100) / limit);
    }

    private String getAttentionReason(Subscription subscription) {
        if (subscription.getStatus() == Subscription.SubscriptionStatus.PAST_DUE) {
            return "Payment failed - please update payment method";
        }
        if (subscription.getStatus() == Subscription.SubscriptionStatus.INCOMPLETE) {
            return "Subscription setup incomplete - please complete payment";
        }
        if (subscription.isTrialEndingSoon()) {
            return String.format("Trial ends in %d days", subscription.getTrialDaysRemaining());
        }
        return "Subscription needs attention";
    }

    private Map<String, String> generateWarnings(Business business, PlanPolicy.PlanEntitlement entitlement, UsageService.UsageStats usage) {
        Map<String, String> warnings = new HashMap<>();

        // Customer limit warnings
        if (usage.totalCustomers >= entitlement.getMaxCustomers() * 0.9) {
            warnings.put("customers", String.format("Approaching customer limit (%d/%d)", usage.totalCustomers, entitlement.getMaxCustomers()));
        }

        // SMS overage warning
        if (usage.smsSent > entitlement.getIncludedSmsPerMonth()) {
            int overage = usage.smsSent - entitlement.getIncludedSmsPerMonth();
            warnings.put("sms", String.format("SMS overage: %d messages will be charged", overage));
        } else if (usage.smsSent >= entitlement.getIncludedSmsPerMonth() * 0.9) {
            warnings.put("sms", String.format("Approaching SMS limit (%d/%d)", usage.smsSent, entitlement.getIncludedSmsPerMonth()));
        }

        // Daily request warning
        int todaysRequests = getTodaysRequestCount(business);
        if (todaysRequests >= entitlement.getMaxRequestsPerDay() * 0.9) {
            warnings.put("dailyRequests", String.format("Approaching daily request limit (%d/%d)", todaysRequests, entitlement.getMaxRequestsPerDay()));
        }

        return warnings;
    }

    private Map<String, Boolean> generateLimitStatus(Business business, PlanPolicy.PlanEntitlement entitlement, UsageService.UsageStats usage) {
        Map<String, Boolean> limits = new HashMap<>();

        limits.put("customersAtLimit", usage.totalCustomers >= entitlement.getMaxCustomers());
        limits.put("dailyRequestsAtLimit", getTodaysRequestCount(business) >= entitlement.getMaxRequestsPerDay());
        limits.put("smsOverLimit", usage.smsSent > entitlement.getIncludedSmsPerMonth());

        return limits;
    }

    // Result classes for enforcement decisions

    public static class EnforcementResult {
        private final boolean allowed;
        private final String message;
        private final String reason;
        private final String upgradeUrl;
        private final boolean hasWarning;

        private EnforcementResult(boolean allowed, String message, String reason, String upgradeUrl, boolean hasWarning) {
            this.allowed = allowed;
            this.message = message;
            this.reason = reason;
            this.upgradeUrl = upgradeUrl;
            this.hasWarning = hasWarning;
        }

        public static EnforcementResult allowed(String message) {
            return new EnforcementResult(true, message, null, null, false);
        }

        public static EnforcementResult allowedWithWarning(String message, String reason) {
            return new EnforcementResult(true, message, reason, null, true);
        }

        public static EnforcementResult blocked(String message, String reason, String upgradeUrl) {
            return new EnforcementResult(false, message, reason, upgradeUrl, false);
        }

        public boolean isAllowed() { return allowed; }
        public String getMessage() { return message; }
        public String getReason() { return reason; }
        public String getUpgradeUrl() { return upgradeUrl; }
        public boolean hasWarning() { return hasWarning; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("allowed", allowed);
            map.put("message", message);
            if (reason != null) map.put("reason", reason);
            if (upgradeUrl != null) map.put("upgradeUrl", upgradeUrl);
            if (hasWarning) map.put("hasWarning", true);
            return map;
        }
    }
}