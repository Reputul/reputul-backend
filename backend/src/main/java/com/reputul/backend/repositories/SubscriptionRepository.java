package com.reputul.backend.repositories;

import com.reputul.backend.models.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Subscription findByBusinessId(Long businessId);
}
