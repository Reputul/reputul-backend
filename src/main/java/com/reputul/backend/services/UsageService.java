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

/**
 * Service for tracking and managing usage across the platform
 * with tight integration to Stripe billing for overage charges
 */
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

    /**
     * Record SMS usage and bill overages to Stripe if necessary
     */
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
            // Don't throw - usage tracking shouldn't break core functionality
        }
    }

    /**
     * Record email usage (currently unlimited for all plans)
     */
    @Transactional
    public void recordEmailUsage(Business business, String requestId) {
        try {
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT, requestId);
            usageEvent.setOverageBilled(false); // Emails are unlimited

            usageEventRepository.save(usageEvent);
            log.debug("Recorded email usage for business {}", business.getId());

        } catch (Exception e) {
            log.error("Failed to record email usage for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    /**
     * Record review request usage (for rate limiting)
     */
    @Transactional
    public void recordReviewRequestUsage(Business business, String requestId) {
        try {
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.REVIEW_REQUEST_SENT, requestId);
            usageEventRepository.save(usageEvent);
            log.debug("Recorded review request usage for business {}", business.getId());

        } catch (Exception e) {
            log.error("Failed to record review request usage for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    /**
     * Record customer creation usage (for plan limits)
     */
    @Transactional
    public void recordCustomerCreation(Business business, Long customerId) {
        try {
            UsageEvent usageEvent = createUsageEvent(business, UsageEvent.UsageType.CUSTOMER_CREATED, customerId.toString());
            usageEventRepository.save(usageEvent);
            log.debug("Recorded customer creation for business {}", business.getId());

        } catch (Exception e) {
            log.error("Failed to record customer creation for business {}: {}", business.getId(), e.getMessage(), e);
        }
    }

    /**
     * Get comprehensive usage statistics for a business
     */
    public UsageStats getCurrentPeriodUsage(Business business) {
        OffsetDateTime periodStart = getCurrentPeriodStart(business);
        OffsetDateTime periodEnd = getCurrentPeriodEnd(business);

        return UsageStats.builder()
                .businessId(business.getId())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .smsSent(usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                        business, UsageEvent.UsageType.SMS_REVIEW_REQUEST_SENT, periodStart, periodEnd))
                .emailSent(usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                        business, UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT, periodStart, periodEnd))
                .reviewRequestsSent(usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                        business, UsageEvent.UsageType.REVIEW_REQUEST_SENT, periodStart, periodEnd))
                .customersCreated(usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                        business, UsageEvent.UsageType.CUSTOMER_CREATED, periodStart, periodEnd))
                .totalCustomers(customerRepository.countByBusiness(business))
                .build();
    }

    /**
     * Get usage summary formatted for API responses
     */
    public Map<String, Object> getUsageSummaryForApi(Business business) {
        UsageStats usage = getCurrentPeriodUsage(business);
        PlanPolicy.PlanEntitlement entitlement = getBusinessEntitlement(business);

        // Get today's usage for rate limiting
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0);
        OffsetDateTime todayEnd = todayStart.plusDays(1);

        int requestsToday = usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                business, UsageEvent.UsageType.REVIEW_REQUEST_SENT, todayStart, todayEnd);

        Map<String, Object> summary = new HashMap<>();

        // Current period usage
        summary.put("smsSent", usage.smsSent);
        summary.put("smsIncluded", entitlement.getIncludedSmsPerMonth());
        summary.put("smsOverage", Math.max(0, usage.smsSent - entitlement.getIncludedSmsPerMonth()));

        summary.put("emailSent", usage.emailSent);
        summary.put("emailIncluded", entitlement.getIncludedEmailPerMonth()); // -1 = unlimited

        summary.put("requestsToday", requestsToday);
        summary.put("maxRequestsPerDay", entitlement.getMaxRequestsPerDay());

        summary.put("totalCustomers", usage.totalCustomers);
        summary.put("maxCustomers", entitlement.getMaxCustomers());

        // Calculate usage percentages
        summary.put("smsUsagePercent", calculateUsagePercent(usage.smsSent, entitlement.getIncludedSmsPerMonth()));
        summary.put("dailyRequestsPercent", calculateUsagePercent(requestsToday, entitlement.getMaxRequestsPerDay()));
        summary.put("customersPercent", calculateUsagePercent(usage.totalCustomers, entitlement.getMaxCustomers()));

        // Period info
        summary.put("periodStart", usage.periodStart);
        summary.put("periodEnd", usage.periodEnd);

        return summary;
    }

    /**
     * Get usage history for analytics (last N months)
     */
    public List<Map<String, Object>> getUsageHistory(Business business, int months) {
        List<Map<String, Object>> history = new ArrayList<>();

        for (int i = months - 1; i >= 0; i--) {
            OffsetDateTime monthStart = OffsetDateTime.now().minusMonths(i).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
            OffsetDateTime monthEnd = monthStart.plusMonths(1);

            int smsCount = usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                    business, UsageEvent.UsageType.SMS_REVIEW_REQUEST_SENT, monthStart, monthEnd);
            int emailCount = usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                    business, UsageEvent.UsageType.EMAIL_REVIEW_REQUEST_SENT, monthStart, monthEnd);
            int requestCount = usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                    business, UsageEvent.UsageType.REVIEW_REQUEST_SENT, monthStart, monthEnd);

            Map<String, Object> monthData = Map.of(
                    "month", monthStart.getMonth().toString(),
                    "year", monthStart.getYear(),
                    "sms", smsCount,
                    "email", emailCount,
                    "requests", requestCount,
                    "periodStart", monthStart,
                    "periodEnd", monthEnd
            );

            history.add(monthData);
        }

        return history;
    }

    /**
     * Check if business is over SMS limit
     */
    public boolean isOverSmsLimit(Business business) {
        UsageStats usage = getCurrentPeriodUsage(business);
        PlanPolicy.PlanEntitlement entitlement = getBusinessEntitlement(business);
        return usage.smsSent >= entitlement.getIncludedSmsPerMonth();
    }

    /**
     * Check if business is over daily request limit
     */
    public boolean isOverDailyRequestLimit(Business business) {
        OffsetDateTime todayStart = OffsetDateTime.now().withHour(0).withMinute(0).withSecond(0);
        OffsetDateTime todayEnd = todayStart.plusDays(1);

        int requestsToday = usageEventRepository.countByBusinessAndTypeAndCreatedAtBetween(
                business, UsageEvent.UsageType.REVIEW_REQUEST_SENT, todayStart, todayEnd);

        PlanPolicy.PlanEntitlement entitlement = getBusinessEntitlement(business);
        return requestsToday >= entitlement.getMaxRequestsPerDay();
    }

    /**
     * Check if business is over customer limit
     */
    public boolean isOverCustomerLimit(Business business) {
        long totalCustomers = customerRepository.countByBusiness(business);
        PlanPolicy.PlanEntitlement entitlement = getBusinessEntitlement(business);
        return totalCustomers >= entitlement.getMaxCustomers();
    }

    // Private helper methods

    private UsageEvent createUsageEvent(Business business, UsageEvent.UsageType type, String referenceId) {
        return UsageEvent.builder()
                .business(business)
                .type(type)
                .referenceId(referenceId)
                .createdAt(OffsetDateTime.now())
                .overageBilled(false)
                .build();
    }

    private boolean determineIfSmsOverage(Business business, int totalSmsCount) {
        PlanPolicy.PlanEntitlement entitlement = getBusinessEntitlement(business);
        return totalSmsCount > entitlement.getIncludedSmsPerMonth();
    }

    private void createStripeUsageRecord(Business business, String requestId) {
        try {
            Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());
            if (subscriptionOpt.isEmpty()) {
                log.warn("No active subscription found for business {} - cannot bill SMS overage", business.getId());
                return;
            }

            Subscription subscription = subscriptionOpt.get();
            if (subscription.getSmsSubscriptionItemId() == null) {
                log.warn("No SMS subscription item ID found for business {} - cannot bill SMS overage", business.getId());
                return;
            }

            // Create usage record in Stripe for 1 SMS
            String idempotencyKey = "sms_" + business.getId() + "_" + requestId + "_" + System.currentTimeMillis();
            stripeService.createUsageRecord(subscription.getSmsSubscriptionItemId(), 1, idempotencyKey);

        } catch (StripeException e) {
            log.error("Failed to create Stripe usage record for business {}: {}", business.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to bill SMS overage", e);
        }
    }

    private PlanPolicy.PlanEntitlement getBusinessEntitlement(Business business) {
        Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());

        if (subscriptionOpt.isEmpty()) {
            // No subscription = free tier with minimal limits
            return PlanPolicy.PlanEntitlement.builder()
                    .maxCustomers(10)
                    .maxRequestsPerDay(5)
                    .includedSmsPerMonth(0)
                    .includedEmailPerMonth(50)
                    .build();
        }

        Subscription subscription = subscriptionOpt.get();
        return planPolicy.getEntitlement(subscription.getPlan());
    }

    private OffsetDateTime getCurrentPeriodStart(Business business) {
        Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());

        if (subscriptionOpt.isPresent() && subscriptionOpt.get().getCurrentPeriodStart() != null) {
            return subscriptionOpt.get().getCurrentPeriodStart();
        }

        // Default to start of current month
        return OffsetDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
    }

    private OffsetDateTime getCurrentPeriodEnd(Business business) {
        Optional<Subscription> subscriptionOpt = subscriptionRepository.findActiveByBusinessId(business.getId());

        if (subscriptionOpt.isPresent() && subscriptionOpt.get().getCurrentPeriodEnd() != null) {
            return subscriptionOpt.get().getCurrentPeriodEnd();
        }

        // Default to end of current month
        return OffsetDateTime.now().withDayOfMonth(1).plusMonths(1).minusSeconds(1);
    }

    private int calculateUsagePercent(long used, int limit) {
        if (limit <= 0) return 0; // Unlimited
        if (limit == -1) return 0; // Unlimited indicator
        return (int) Math.min(100, (used * 100) / limit);
    }

    // Inner classes for data transfer

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class UsageStats {
        public Long businessId;
        public OffsetDateTime periodStart;
        public OffsetDateTime periodEnd;
        public int smsSent;
        public int emailSent;
        public int reviewRequestsSent;
        public int customersCreated;
        public long totalCustomers;
    }
}