package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.SubscriptionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Legacy subscription controller - UPDATED for backward compatibility
 * Use BillingController for new Stripe integration
 */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionRepository subscriptionRepo;
    private final BusinessRepository businessRepo;

    public SubscriptionController(SubscriptionRepository subscriptionRepo, BusinessRepository businessRepo) {
        this.subscriptionRepo = subscriptionRepo;
        this.businessRepo = businessRepo;
    }

    @PostMapping("/{businessId}")
    public Subscription createOrUpdate(@PathVariable Long businessId, @RequestBody Subscription subscription) {
        Business business = businessRepo.findById(businessId).orElseThrow();
        subscription.setBusiness(business);

        // Use new field but also set legacy field for compatibility
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        subscription.setStartDate(now);
        subscription.setCurrentPeriodStart(now);

        if (subscription.isTrial()) {
            subscription.setStatus(Subscription.SubscriptionStatus.TRIALING);
            subscription.setTrial(true); // Legacy field

            OffsetDateTime trialEnd = now.plusDays(14);
            subscription.setEndDate(trialEnd);
            subscription.setTrialEnd(trialEnd);
        } else {
            subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            subscription.setTrial(false);

            OffsetDateTime renewalDate = now.plusMonths(1);
            subscription.setRenewalDate(renewalDate);
            subscription.setCurrentPeriodEnd(renewalDate);
        }

        // Ensure legacy fields are synced
        subscription.syncLegacyFields();

        return subscriptionRepo.save(subscription);
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<Subscription> getByBusiness(@PathVariable Long businessId) {
        return subscriptionRepo.findByBusinessId(businessId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}