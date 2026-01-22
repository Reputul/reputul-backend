package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for pricing information displayed to users
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingDto {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanDto {
        private String name;
        private String displayName;
        private Integer monthlyPrice;
        private String description;
        private List<String> features;
        private PlanLimits limits;
        private Boolean popular;
        private Boolean currentPlan;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PlanLimits {
        private Integer maxBusinesses;
        private String maxBusinessesDisplay; // "1 business" or "3 businesses" or "10 businesses"
        private Integer maxUsers;
        private String maxUsersDisplay; // "1 user" or "5 users"
        private Integer smsLimit;
        private String smsLimitDisplay; // "100 SMS/month"
        private Integer reviewRequestLimit;
        private String reviewRequestLimitDisplay; // "100 review requests/month"
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CurrentSubscriptionDto {
        private String plan;
        private String status;
        private String statusDisplay;
        private Boolean inTrial;
        private String trialEndsAt;
        private Boolean isBetaTester;
        private String betaExpiresAt;
        private String currentPeriodEnd;
        private UsageDto usage;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UsageDto {
        private Integer smsUsed;
        private Integer smsLimit;
        private Double smsUsagePercent;
        private Integer reviewRequestsUsed;
        private Integer reviewRequestsLimit;
        private Double reviewRequestsUsagePercent;
        private Integer businessesUsed;
        private Integer businessesLimit;
        private Integer usersUsed;
        private Integer usersLimit;
    }

    private List<PlanDto> plans;
    private CurrentSubscriptionDto currentSubscription;
    private Boolean hasActiveSubscription;
    private Boolean canUpgrade;
    private Boolean canDowngrade;
    private TrialInfo trialInfo;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TrialInfo {
        private Boolean available;
        private Integer daysRemaining;
        private String message;
    }

    /**
     * Create default pricing info with all plans
     */
    public static PricingDto createDefault() {
        return PricingDto.builder()
                .plans(List.of(
                        createSoloPlan(),
                        createProPlan(),
                        createGrowthPlan()
                ))
                .hasActiveSubscription(false)
                .canUpgrade(false)
                .canDowngrade(false)
                .trialInfo(TrialInfo.builder()
                        .available(true)
                        .daysRemaining(14)
                        .message("14-day free trial, no credit card required")
                        .build())
                .build();
    }

    private static PlanDto createSoloPlan() {
        return PlanDto.builder()
                .name("SOLO")
                .displayName("Solo")
                .monthlyPrice(59)
                .description("Perfect for individual contractors getting started")
                .features(List.of(
                        "1 business location",
                        "100 review requests/month",
                        "100 SMS messages/month included",
                        "Email review requests (unlimited)",
                        "Google & Facebook integration",
                        "Review widgets for your website",
                        "Basic analytics dashboard",
                        "Email support"
                ))
                .limits(PlanLimits.builder()
                        .maxBusinesses(1)
                        .maxBusinessesDisplay("1 business")
                        .maxUsers(1)
                        .maxUsersDisplay("1 user")
                        .smsLimit(100)
                        .smsLimitDisplay("100 SMS/month")
                        .reviewRequestLimit(100)
                        .reviewRequestLimitDisplay("100 requests/month")
                        .build())
                .popular(false)
                .build();
    }

    private static PlanDto createProPlan() {
        return PlanDto.builder()
                .name("PRO")
                .displayName("Pro")
                .monthlyPrice(99)
                .description("For growing businesses managing multiple locations")
                .features(List.of(
                        "3 business locations",
                        "500 review requests/month",
                        "300 SMS messages/month included",
                        "Email review requests (unlimited)",
                        "Google & Facebook integration",
                        "Review widgets for your website",
                        "Advanced analytics & insights",
                        "AI-powered review responses",
                        "Team collaboration (5 users)",
                        "Priority email support"
                ))
                .limits(PlanLimits.builder()
                        .maxBusinesses(3)
                        .maxBusinessesDisplay("3 businesses")
                        .maxUsers(5)
                        .maxUsersDisplay("5 users")
                        .smsLimit(300)
                        .smsLimitDisplay("300 SMS/month")
                        .reviewRequestLimit(500)
                        .reviewRequestLimitDisplay("500 requests/month")
                        .build())
                .popular(true)
                .build();
    }

    private static PlanDto createGrowthPlan() {
        return PlanDto.builder()
                .name("GROWTH")
                .displayName("Growth")
                .monthlyPrice(149)
                .description("For established businesses scaling their operations")
                .features(List.of(
                        "10 business locations",
                        "2,000 review requests/month",
                        "1,000 SMS messages/month included",
                        "Email review requests (unlimited)",
                        "Google & Facebook integration",
                        "Review widgets for your website",
                        "Advanced analytics & insights",
                        "AI-powered review responses",
                        "Automated review campaigns",
                        "Team collaboration (20 users)",
                        "API access",
                        "White-label options",
                        "Dedicated account manager",
                        "Phone & priority support"
                ))
                .limits(PlanLimits.builder()
                        .maxBusinesses(10)
                        .maxBusinessesDisplay("10 businesses")
                        .maxUsers(20)
                        .maxUsersDisplay("20 users")
                        .smsLimit(1000)
                        .smsLimitDisplay("1,000 SMS/month")
                        .reviewRequestLimit(2000)
                        .reviewRequestLimitDisplay("2,000 requests/month")
                        .build())
                .popular(false)
                .build();
    }
}