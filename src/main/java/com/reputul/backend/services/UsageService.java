package com.reputul.backend.services;

import com.reputul.backend.config.PlanPolicy;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import com.stripe.exception.StripeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Slf4j
public class UsageService {

    private final UsageEventRepository usageEventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final PlanPolicy planPolicy;
    private final StripeService stripeService;

    public UsageService(UsageEventRepository usageEventRepository,
                        SubscriptionRepository subscriptionRepository,
                        CustomerRepository customerRepository,
                        BusinessRepository businessRepository,
                        PlanPolicy planPolicy,
                        StripeService stripeService) {
        this.usageEventRepository = usageEventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.customerRepository = customerRepository;
        this.businessRepository = businessRepository;
        this.planPolicy = planPolicy;
        this.stripeService = stripeService;
    }

    @Transactional
    public void recordSmsUsage(Business business, String requestId) {
        try {
            // Create usage event
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.SMS_REVIEW_REQUEST_SENT, requestId);

            // Get current period usage for business
            UsageStats usage = getCurrentPeriodUsage(business);

            // Check if this SMS puts us over the included allowance
            boolean isOverage = determineIfSmsOverage(business, usage.smsSent + 1);

            if (isOverage) {
                // Create Stripe usage record for billing
                createStripeUsageRecord(business, requestId);
                usageEvent.setOverageBilled(true);
                log.info("SMS overage billed for business {} - total SMS: {}", business.getId(), usage.smsSent + 1);
            }

            usageEventRepository.save(usageEvent);
            log.debug("Recorded SMS usage for business {}, overage: {}", business.getId(), isOverage);

        } catch (Exception e) {
            log.error("Failed to record SMS usage for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void recordEmailUsage(Business business, String requestId) {
        try {
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT, requestId);
            usageEventRepository.save(usageEvent);

            log.debug("Recorded email usage for business {}", business.getId());
        } catch (Exception e) {
            log.error("Failed to record email usage for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    @Transactional
    public void recordCustomerCreation(Business business, String requestId) {
        try {
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.CUSTOMER_CREATED, requestId);
            usageEventRepository.save(usageEvent);

            log.debug("Recorded customer creation for business {}", business.getId());
        } catch (Exception e) {
            log.error("Failed to record customer creation for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    private UsageEvent createUsageEvent(Business business, UsageEvent.UsageType usageType, String requestId) {
        Subscription subscription = subscriptionRepository.findByBusinessId(business.getId())
                .orElse(null);

        OffsetDateTime periodStart = null;
        OffsetDateTime periodEnd = null;

        if (subscription != null && subscription.getCurrentPeriodStart() != null) {
            periodStart = subscription.getCurrentPeriodStart();
            periodEnd = subscription.getCurrentPeriodEnd();
        }

        return UsageEvent.builder()
                .business(business)
                .usageType(usageType)
                .requestId(requestId)
                .billingPeriodStart(periodStart)
                .billingPeriodEnd(periodEnd)
                .occurredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .overageBilled(false)
                .build();
    }

    private void createStripeUsageRecord(Business business, String requestId) {
        try {
            Subscription subscription = subscriptionRepository.findByBusinessId(business.getId())
                    .orElse(null);

            if (subscription == null || subscription.getSmsSubscriptionItemId() == null) {
                log.warn("Cannot create Stripe usage record: subscription or SMS item missing for business {}", business.getId());
                return;
            }

            stripeService.createUsageRecord(
                    subscription.getSmsSubscriptionItemId(),
                    1, // quantity
                    requestId // idempotency key
            );

        } catch (StripeException e) {
            log.error("Failed to create Stripe usage record for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    private boolean determineIfSmsOverage(Business business, int newTotal) {
        Subscription subscription = subscriptionRepository.findByBusinessId(business.getId())
                .orElse(null);

        if (subscription == null || !subscription.isActive()) {
            return false; // No billing if no active subscription
        }

        PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(subscription.getPlan());
        return newTotal > entitlement.getIncludedSmsPerMonth();
    }

    public UsageStats getCurrentPeriodUsage(Business business) {
        Subscription subscription = subscriptionRepository.findByBusinessId(business.getId())
                .orElse(null);

        OffsetDateTime periodStart; // Changed from OffsetDateTime
        OffsetDateTime periodEnd;   // Changed from OffsetDateTime

        if (subscription != null && subscription.getCurrentPeriodStart() != null) {
            periodStart = subscription.getCurrentPeriodStart();
            periodEnd = subscription.getCurrentPeriodEnd();
        } else {
            // Default to current month if no subscription
            periodStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(); // Changed
            periodEnd = periodStart.plusMonths(1);
        }

        // Count SMS usage in current period
        int smsSent = usageEventRepository.countByBusinessAndUsageTypeAndOccurredAtBetween(
                business,
                UsageEvent.UsageType.SMS_REVIEW_REQUEST_SENT,
                periodStart,
                periodEnd
        );

        // Count email usage in current period
        int emailSent = usageEventRepository.countByBusinessAndUsageTypeAndOccurredAtBetween(
                business,
                UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT,
                periodStart,
                periodEnd
        );

        // Count today's requests for daily limit checking
        OffsetDateTime todayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime todayEnd = todayStart.plusDays(1);

        int requestsToday = usageEventRepository.countByBusinessAndUsageTypeInAndOccurredAtBetween(
                business,
                Arrays.asList(UsageEvent.UsageType.SMS_REVIEW_REQUEST_SENT, UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT),
                todayStart,
                todayEnd
        );

        // Count total customers
        int totalCustomers = (int) customerRepository.countByBusiness(business);

        return UsageStats.builder()
                .business(business)
                .subscription(subscription)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .smsSent(smsSent)
                .emailSent(emailSent)
                .requestsToday(requestsToday)
                .totalCustomers(totalCustomers)
                .build();
    }

    public Map<String, Object> getUsageSummaryForApi(Business business) {
        UsageStats stats = getCurrentPeriodUsage(business);
        PlanPolicy.PlanEntitlement entitlement = null;

        if (stats.getSubscription() != null) {
            entitlement = planPolicy.getEntitlement(stats.getSubscription().getPlan());
        }

        Map<String, Object> summary = new HashMap<>();

        // Usage data
        summary.put("smsSent", stats.getSmsSent());
        summary.put("emailSent", stats.getEmailSent());
        summary.put("requestsToday", stats.getRequestsToday());
        summary.put("totalCustomers", stats.getTotalCustomers());
        summary.put("periodStart", stats.getPeriodStart());
        summary.put("periodEnd", stats.getPeriodEnd());

        // Plan limits and overages
        if (entitlement != null) {
            summary.put("smsIncluded", entitlement.getIncludedSmsPerMonth());
            summary.put("emailIncluded", entitlement.hasUnlimitedEmail() ? -1 : entitlement.getIncludedEmailPerMonth());
            summary.put("maxRequestsPerDay", entitlement.getMaxRequestsPerDay());
            summary.put("maxCustomers", entitlement.getMaxCustomers());

            summary.put("smsOverage", entitlement.getSmsOverage(stats.getSmsSent()));
            summary.put("emailOverage", entitlement.getEmailOverage(stats.getEmailSent()));

            // Usage percentages
            summary.put("smsUsagePercent", Math.min(100, (stats.getSmsSent() * 100) / entitlement.getIncludedSmsPerMonth()));
            summary.put("dailyRequestsPercent", Math.min(100, (stats.getRequestsToday() * 100) / entitlement.getMaxRequestsPerDay()));
            summary.put("customersPercent", Math.min(100, (stats.getTotalCustomers() * 100) / entitlement.getMaxCustomers()));
        }

        return summary;
    }

    // Get historical usage for analytics
    public List<Map<String, Object>> getUsageHistory(Business business, int months) {
        OffsetDateTime endDate = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime startDate = endDate.minusMonths(months);

        List<Object[]> monthlyUsage = usageEventRepository.findMonthlyUsageByBusiness(business.getId(), startDate, endDate);

        List<Map<String, Object>> history = new ArrayList<>();
        for (Object[] row : monthlyUsage) {
            Map<String, Object> monthData = new HashMap<>();
            monthData.put("month", row[0]); // YYYY-MM format
            monthData.put("smsSent", row[1]);
            monthData.put("emailSent", row[2]);
            monthData.put("totalRequests", ((Number) row[1]).intValue() + ((Number) row[2]).intValue());
            history.add(monthData);
        }

        return history;
    }

    // Reset daily counters (could be called by a scheduled job)
    public void resetDailyCounters() {
        log.info("Resetting daily usage counters");
        // Implementation would reset daily counters in a usage_periods table if we had one
    }

    // Usage stats data class
    @lombok.Data
    @lombok.Builder
    public static class UsageStats {
        private Business business;
        private Subscription subscription;
        private OffsetDateTime periodStart;
        private OffsetDateTime periodEnd;
        private int smsSent;
        private int emailSent;
        private int requestsToday;
        private int totalCustomers;

        public boolean hasSubscription() {
            return subscription != null;
        }

        public boolean isActiveSubscription() {
            return hasSubscription() && subscription.isActive();
        }
    }
}