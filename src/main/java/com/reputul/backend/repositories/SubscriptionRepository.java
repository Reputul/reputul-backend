package com.reputul.backend.repositories;

import com.reputul.backend.models.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByBusinessId(Long businessId);

    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    @Query("SELECT s FROM Subscription s WHERE s.business.user.id = :userId")
    Optional<Subscription> findByBusinessUserId(Long userId);

    @Query("SELECT s FROM Subscription s WHERE s.business.user.id = :userId ORDER BY s.createdAt DESC")
    List<Subscription> findAllByBusinessUserId(Long userId);

    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    @Query("SELECT s FROM Subscription s WHERE s.promoCode IS NOT NULL AND s.promoPhase = 1")
    List<Subscription> findActivePromoSubscriptions();

    @Query("SELECT s FROM Subscription s WHERE s.trialEnd IS NOT NULL AND s.trialEnd < CURRENT_TIMESTAMP AND s.status = 'TRIALING'")
    List<Subscription> findExpiredTrials();
}