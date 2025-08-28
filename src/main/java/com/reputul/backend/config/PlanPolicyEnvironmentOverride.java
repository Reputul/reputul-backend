package com.reputul.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Environment-based configuration support
@Component
@Slf4j
class PlanPolicyEnvironmentOverride {

    private final PlanPolicy planPolicy;

    public PlanPolicyEnvironmentOverride(PlanPolicy planPolicy) {
        this.planPolicy = planPolicy;
    }

    @PostConstruct
    public void applyEnvironmentOverrides() {
        // Allow environment variables to override plan limits
        // Format: PLAN_SOLO_MAX_CUSTOMERS=200
        //         PLAN_PRO_SMS_INCLUDED=150

        applyOverrideIfPresent("SOLO");
        applyOverrideIfPresent("PRO");
        applyOverrideIfPresent("GROWTH");
    }

    private void applyOverrideIfPresent(String planName) {
        PlanPolicy.PlanEntitlement entitlement = planPolicy.getEntitlement(planName);
        if (entitlement == null) return;

        String prefix = "PLAN_" + planName + "_";

        String maxCustomers = System.getenv(prefix + "MAX_CUSTOMERS");
        if (maxCustomers != null) {
            try {
                entitlement.setMaxCustomers(Integer.parseInt(maxCustomers));
                log.info("Override applied: {} max customers = {}", planName, maxCustomers);
            } catch (NumberFormatException e) {
                log.warn("Invalid override value for {}: {}", prefix + "MAX_CUSTOMERS", maxCustomers);
            }
        }

        String maxRequestsPerDay = System.getenv(prefix + "MAX_REQUESTS_PER_DAY");
        if (maxRequestsPerDay != null) {
            try {
                entitlement.setMaxRequestsPerDay(Integer.parseInt(maxRequestsPerDay));
                log.info("Override applied: {} max requests per day = {}", planName, maxRequestsPerDay);
            } catch (NumberFormatException e) {
                log.warn("Invalid override value for {}: {}", prefix + "MAX_REQUESTS_PER_DAY", maxRequestsPerDay);
            }
        }

        String includedSms = System.getenv(prefix + "SMS_INCLUDED");
        if (includedSms != null) {
            try {
                entitlement.setIncludedSmsPerMonth(Integer.parseInt(includedSms));
                log.info("Override applied: {} included SMS = {}", planName, includedSms);
            } catch (NumberFormatException e) {
                log.warn("Invalid override value for {}: {}", prefix + "SMS_INCLUDED", includedSms);
            }
        }

        String includedEmail = System.getenv(prefix + "EMAIL_INCLUDED");
        if (includedEmail != null) {
            try {
                entitlement.setIncludedEmailPerMonth(Integer.parseInt(includedEmail));
                log.info("Override applied: {} included email = {}", planName, includedEmail);
            } catch (NumberFormatException e) {
                log.warn("Invalid override value for {}: {}", prefix + "EMAIL_INCLUDED", includedEmail);
            }
        }
    }
}
