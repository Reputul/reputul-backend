package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.SubscriptionRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

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
        subscription.setStartDate(LocalDateTime.now());
        if (subscription.isTrial()) {
            subscription.setStatus("trialing");
            subscription.setEndDate(LocalDateTime.now().plusDays(14));
        } else {
            subscription.setStatus("active");
            subscription.setRenewalDate(LocalDateTime.now().plusMonths(1));
        }
        return subscriptionRepo.save(subscription);
    }

    @GetMapping("/business/{businessId}")
    public Subscription getByBusiness(@PathVariable Long businessId) {
        return subscriptionRepo.findByBusinessId(businessId);
    }
}
