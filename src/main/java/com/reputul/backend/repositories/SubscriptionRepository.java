package com.reputul.backend.repositories;

import com.reputul.backend.models.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for subscription entities with comprehensive querying capabilities
 * for Stripe integration and subscription management
 */
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * Find subscription by business ID
     */
    Optional<Subscription> findByBusinessId(Long businessId);

    /**
     * Find subscription by Stripe subscription ID
     * Critical for webhook processing
     */
    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);

    /**
     * Find subscription by business user ID (used in StripeService.getSubscriptionSummary)
     * This finds the subscription associated with a user's business
     */
    @Query("SELECT s FROM Subscription s JOIN s.business b WHERE b.user.id = :userId")
    Optional<Subscription> findByBusinessUserId(@Param("userId") Long userId);

    /**
     * Find active subscription for a business
     * Active includes ACTIVE and TRIALING statuses
     */
    @Query("SELECT s FROM Subscription s WHERE s.business.id = :businessId AND s.status IN ('ACTIVE', 'TRIALING')")
    Optional<Subscription> findActiveByBusinessId(@Param("businessId") Long businessId);

    /**
     * Find subscription by Stripe customer ID
     */
    Optional<Subscription> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Find all active subscriptions (for billing reconciliation)
     */
    @Query("SELECT s FROM Subscription s WHERE s.status IN ('ACTIVE', 'TRIALING', 'PAST_DUE')")
    List<Subscription> findAllActiveSubscriptions();

    /**
     * Find subscriptions ending trial soon (for notifications)
     */
    @Query("SELECT s FROM Subscription s WHERE s.status = 'TRIALING' AND s.trialEnd BETWEEN :start AND :end")
    List<Subscription> findTrialsEndingSoon(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /**
     * Find subscriptions with past due status
     */
    List<Subscription> findByStatus(Subscription.SubscriptionStatus status);

    /**
     * Find subscriptions by plan type
     */
    List<Subscription> findByPlan(Subscription.PlanType plan);

    /**
     * Check if user has ever had a subscription (for trial eligibility)
     */
    @Query("SELECT COUNT(s) > 0 FROM Subscription s JOIN s.business b WHERE b.user.id = :userId")
    boolean existsByBusinessUserId(@Param("userId") Long userId);

    /**
     * Find subscriptions created in a date range (for analytics)
     */
    @Query("SELECT s FROM Subscription s WHERE s.createdAt BETWEEN :start AND :end")
    List<Subscription> findByCreatedAtBetween(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    /**
     * Find subscriptions with promo codes
     */
    List<Subscription> findByPromoCodeIsNotNull();

    /**
     * Find subscriptions by promo code
     */
    List<Subscription> findByPromoCode(String promoCode);

    /**
     * Count active subscriptions by plan (for analytics)
     */
    @Query("SELECT s.plan, COUNT(s) FROM Subscription s WHERE s.status IN ('ACTIVE', 'TRIALING') GROUP BY s.plan")
    List<Object[]> countActiveSubscriptionsByPlan();

    /**
     * Find subscriptions needing period end processing
     */
    @Query("SELECT s FROM Subscription s WHERE s.currentPeriodEnd < :cutoff AND s.status = 'ACTIVE'")
    List<Subscription> findSubscriptionsNeedingPeriodEndProcessing(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Find user's most recent subscription (for upgrade/downgrade scenarios)
     */
    @Query("SELECT s FROM Subscription s JOIN s.business b WHERE b.user.id = :userId ORDER BY s.createdAt DESC")
    List<Subscription> findByBusinessUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);
}