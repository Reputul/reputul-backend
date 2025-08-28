package com.reputul.backend.dto;

import com.reputul.backend.models.Subscription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingStatusDto {

    // Subscription info
    private boolean hasActiveSubscription;
    private String plan;
    private String status;
    private LocalDateTime currentPeriodStart;
    private LocalDateTime currentPeriodEnd;
    private LocalDateTime trialEnd;

    // Promo info
    private PromoInfoDto promo;

    // Usage info
    private UsageInfoDto usage;

    // Limits and warnings
    private Map<String, Object> limits;
    private Map<String, Object> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromoInfoDto {
        private String code;
        private String description;
        private Integer phase;
        private LocalDateTime endsAt;
        private boolean isInFreePhase;
        private boolean isInDiscountPhase;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsageInfoDto {
        private int smsSent;
        private int smsIncluded;
        private int smsOverage;
        private int emailSent;
        private int emailIncluded;
        private int requestsToday;
        private int maxRequestsPerDay;
        private int totalCustomers;
        private int maxCustomers;

        // Percentages
        private int smsUsagePercent;
        private int dailyRequestsPercent;
        private int customersPercent;
    }
}