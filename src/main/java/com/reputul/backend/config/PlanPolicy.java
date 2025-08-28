package com.reputul.backend.config;

import com.reputul.backend.models.Subscription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.HashMap;

@Component
@Configuration
@ConfigurationProperties(prefix = "app.plans")
@Data
@Slf4j
public class PlanPolicy {

    private Map<String, PlanEntitlement> entitlements = new HashMap<>();

    @PostConstruct
    public void init() {
        // Initialize default plan entitlements if not configured via properties
        if (entitlements.isEmpty()) {
            initializeDefaultEntitlements();
        }

        log.info("Initialized plan entitlements: {}", entitlements.keySet());
        entitlements.forEach((plan, entitlement) ->
                log.info("Plan {}: {} customers, {} requests/day, {} SMS/month, {} emails/month",
                        plan, entitlement.getMaxCustomers(), entitlement.getMaxRequestsPerDay(),
                        entitlement.getIncludedSmsPerMonth(), entitlement.getIncludedEmailPerMonth())
        );
    }

    private void initializeDefaultEntitlements() {
        // SOLO Plan - Basic tier
        entitlements.put("SOLO", PlanEntitlement.builder()
                .maxCustomers(100)
                .maxRequestsPerDay(10)
                .includedSmsPerMonth(25)
                .includedEmailPerMonth(-1) // Unlimited
                .build());

        // PRO Plan - Mid tier
        entitlements.put("PRO", PlanEntitlement.builder()
                .maxCustomers(500)
                .maxRequestsPerDay(50)
                .includedSmsPerMonth(100)
                .includedEmailPerMonth(-1) // Unlimited
                .build());

        // GROWTH Plan - High tier
        entitlements.put("GROWTH", PlanEntitlement.builder()
                .maxCustomers(2000)
                .maxRequestsPerDay(200)
                .includedSmsPerMonth(500)
                .includedEmailPerMonth(-1) // Unlimited
                .build());
    }

    public PlanEntitlement getEntitlement(Subscription.PlanType planType) {
        return getEntitlement(planType.name());
    }

    public PlanEntitlement getEntitlement(String planName) {
        return entitlements.getOrDefault(planName.toUpperCase(),
                entitlements.get("SOLO")); // Default to SOLO if plan not found
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PlanEntitlement {
        private int maxCustomers;
        private int maxRequestsPerDay;
        private int includedSmsPerMonth;
        private int includedEmailPerMonth; // -1 means unlimited

        public boolean hasUnlimitedEmail() {
            return includedEmailPerMonth == -1;
        }

        public boolean allowsCustomerCreation(int currentCount) {
            return currentCount < maxCustomers;
        }

        public boolean allowsDailyRequests(int todayCount) {
            return todayCount < maxRequestsPerDay;
        }

        public boolean hasSmsAllowance(int currentUsage) {
            return currentUsage < includedSmsPerMonth;
        }

        public boolean hasEmailAllowance(int currentUsage) {
            return hasUnlimitedEmail() || currentUsage < includedEmailPerMonth;
        }

        public int getSmsOverage(int currentUsage) {
            return Math.max(0, currentUsage - includedSmsPerMonth);
        }

        public int getEmailOverage(int currentUsage) {
            return hasUnlimitedEmail() ? 0 : Math.max(0, currentUsage - includedEmailPerMonth);
        }
    }
}

