package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.SubscriptionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@RestController
@RequestMapping("/api/subscriptions")
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
        subscription.setStartDate(OffsetDateTime.now(ZoneOffset.UTC));
        if (subscription.isTrial()) {
            subscription.setStatus(Subscription.SubscriptionStatus.TRIALING);
            subscription.setEndDate(OffsetDateTime.now(ZoneOffset.UTC).plusDays(14));
        } else {
            subscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            subscription.setRenewalDate(OffsetDateTime.now(ZoneOffset.UTC).plusMonths(1));
        }
        return subscriptionRepo.save(subscription);
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<Subscription> getByBusiness(@PathVariable Long businessId) {
        return subscriptionRepo.findByBusinessId(businessId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
