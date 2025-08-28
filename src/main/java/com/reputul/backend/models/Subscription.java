package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Stripe Integration Fields
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @Column(name = "stripe_schedule_id")
    private String stripeScheduleId; // For promo schedules

    @Column(name = "sms_subscription_item_id")
    private String smsSubscriptionItemId; // Metered SMS item

    // Plan and Status
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanType plan = PlanType.SOLO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus status = SubscriptionStatus.INACTIVE;

    // Billing Period
    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    // Trial Support
    @Column(name = "trial_start")
    private LocalDateTime trialStart;

    @Column(name = "trial_end")
    private LocalDateTime trialEnd;

    // Promo Code Tracking
    @Column(name = "promo_code")
    private String promoCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "promo_kind")
    private PromoKind promoKind;

    @Column(name = "promo_phase")
    private Integer promoPhase; // 1 = free phase, 2 = discount phase

    @Column(name = "promo_starts_at")
    private LocalDateTime promoStartsAt;

    @Column(name = "promo_ends_at")
    private LocalDateTime promoEndsAt;

    // Legacy fields (keeping for backward compatibility)
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime renewalDate;
    @Builder.Default
    private boolean trial = false;

    // Relationships
    @OneToOne
    @JoinColumn(name = "business_id")
    private Business business;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Enums
    public enum PlanType {
        SOLO("Solo"),
        PRO("Pro"),
        GROWTH("Growth");

        private final String displayName;

        PlanType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum SubscriptionStatus {
        INACTIVE("Inactive"),
        TRIALING("Trialing"),
        ACTIVE("Active"),
        PAST_DUE("Past Due"),
        CANCELED("Canceled"),
        INCOMPLETE("Incomplete"),
        INCOMPLETE_EXPIRED("Incomplete Expired"),
        UNPAID("Unpaid");

        private final String displayName;

        SubscriptionStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum PromoKind {
        BETA_3_FREE_THEN_50("3 months free, then 50% off");

        private final String description;

        PromoKind(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // Helper methods
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING;
    }

    public boolean isTrialing() {
        return status == SubscriptionStatus.TRIALING;
    }

    public boolean isPastDue() {
        return status == SubscriptionStatus.PAST_DUE;
    }

    public boolean isCanceled() {
        return status == SubscriptionStatus.CANCELED;
    }

    public boolean hasPromo() {
        return promoCode != null && !promoCode.trim().isEmpty();
    }

    public boolean isInFreePromoPhase() {
        return hasPromo() && promoPhase != null && promoPhase == 1;
    }

    public boolean isInDiscountPromoPhase() {
        return hasPromo() && promoPhase != null && promoPhase == 2;
    }
}