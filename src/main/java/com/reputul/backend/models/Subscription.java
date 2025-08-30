package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Subscription entity - Updated to support Stripe integration
 * while maintaining backward compatibility with existing code
 */
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

    // NEW: Stripe Integration Fields
    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", unique = true)
    private String stripeSubscriptionId;

    @Column(name = "stripe_schedule_id")
    private String stripeScheduleId; // For promo schedules

    @Column(name = "sms_subscription_item_id")
    private String smsSubscriptionItemId; // Metered SMS item

    // Plan and Status (Enhanced)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlanType plan = PlanType.SOLO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.INACTIVE;

    // NEW: Stripe Billing Period Fields
    @Column(name = "current_period_start")
    private OffsetDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private OffsetDateTime currentPeriodEnd;

    // NEW: Trial Support
    @Column(name = "trial_start")
    private OffsetDateTime trialStart;

    @Column(name = "trial_end")
    private OffsetDateTime trialEnd;

    // NEW: Promo Code Tracking
    @Column(name = "promo_code")
    private String promoCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "promo_kind")
    private PromoKind promoKind;

    @Column(name = "promo_phase")
    private Integer promoPhase; // 1 = free phase, 2 = discount phase

    @Column(name = "promo_starts_at")
    private OffsetDateTime promoStartsAt;

    @Column(name = "promo_ends_at")
    private OffsetDateTime promoEndsAt;

    // EXISTING: Legacy fields (keeping for backward compatibility)
    private OffsetDateTime startDate;
    private OffsetDateTime endDate;
    private OffsetDateTime renewalDate;

    @Builder.Default
    private boolean trial = false;

    // Business relationship
    @OneToOne
    @JoinColumn(name = "business_id")
    private Business business;

    // Audit fields
    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // EXISTING: Enums (Enhanced with new values)
    public enum PlanType {
        SOLO("Solo", "$39/month", 100, 25),
        PRO("Pro", "$89/month", 500, 100),
        GROWTH("Growth", "$149/month", 2000, 500);

        private final String displayName;
        private final String priceDisplay;
        private final int maxCustomers;
        private final int includedSmsPerMonth;

        PlanType(String displayName, String priceDisplay, int maxCustomers, int includedSmsPerMonth) {
            this.displayName = displayName;
            this.priceDisplay = priceDisplay;
            this.maxCustomers = maxCustomers;
            this.includedSmsPerMonth = includedSmsPerMonth;
        }

        // EXISTING: Backward compatible method
        public String getDisplayName() {
            return displayName;
        }

        // NEW: Additional helper methods
        public String getPriceDisplay() { return priceDisplay; }
        public int getMaxCustomers() { return maxCustomers; }
        public int getIncludedSmsPerMonth() { return includedSmsPerMonth; }
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

        // NEW: Helper method
        public boolean isActive() {
            return this == ACTIVE || this == TRIALING;
        }
    }

    public enum PromoKind {
        BETA_3_FREE_THEN_50("3 months free, then 50% off for 3 months"),
        BETA_6_FREE("6 months completely free"),
        LAUNCH_50_OFF("50% off for first 6 months"),
        CUSTOM("Custom promotional terms");

        private final String description;

        PromoKind(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    // EXISTING: Legacy methods (kept for backward compatibility)
    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.TRIALING;
    }

    public boolean isTrialing() {
        return status == SubscriptionStatus.TRIALING || trial;
    }

    public boolean isTrial() {
        return trial || status == SubscriptionStatus.TRIALING;
    }

    public void setTrial(boolean trial) {
        this.trial = trial;
        if (trial) {
            this.status = SubscriptionStatus.TRIALING;
        }
    }

    public boolean isPastDue() {
        return status == SubscriptionStatus.PAST_DUE;
    }

    public boolean isCanceled() {
        return status == SubscriptionStatus.CANCELED;
    }

    // EXISTING: Legacy date methods (mapped to new fields for compatibility)
    public OffsetDateTime getStartDate() {
        return startDate != null ? startDate : currentPeriodStart;
    }

    public void setStartDate(OffsetDateTime startDate) {
        this.startDate = startDate;
        if (this.currentPeriodStart == null) {
            this.currentPeriodStart = startDate;
        }
    }

    public OffsetDateTime getEndDate() {
        return endDate != null ? endDate : trialEnd;
    }

    public void setEndDate(OffsetDateTime endDate) {
        this.endDate = endDate;
        if (this.trialEnd == null && this.status == SubscriptionStatus.TRIALING) {
            this.trialEnd = endDate;
        }
    }

    public OffsetDateTime getRenewalDate() {
        return renewalDate != null ? renewalDate : currentPeriodEnd;
    }

    public void setRenewalDate(OffsetDateTime renewalDate) {
        this.renewalDate = renewalDate;
        if (this.currentPeriodEnd == null) {
            this.currentPeriodEnd = renewalDate;
        }
    }

    // NEW: Stripe integration helper methods
    public boolean hasPromo() {
        return promoCode != null && !promoCode.trim().isEmpty() && promoKind != null;
    }

    public boolean isInFreePromoPhase() {
        return hasPromo() && promoPhase != null && promoPhase == 1
                && promoEndsAt != null && OffsetDateTime.now().isBefore(promoEndsAt);
    }

    public boolean isInDiscountPromoPhase() {
        return hasPromo() && promoPhase != null && promoPhase == 2
                && promoEndsAt != null && OffsetDateTime.now().isBefore(promoEndsAt);
    }

    public long getTrialDaysRemaining() {
        if (!isTrialing() || trialEnd == null) {
            return 0;
        }
        return java.time.Duration.between(OffsetDateTime.now(), trialEnd).toDays();
    }

    public boolean isTrialEndingSoon() {
        return isTrialing() && getTrialDaysRemaining() <= 3;
    }

    public boolean needsAttention() {
        return status == SubscriptionStatus.PAST_DUE
                || status == SubscriptionStatus.INCOMPLETE
                || isTrialEndingSoon();
    }

    public String getStatusDescription() {
        if (isTrialing()) {
            long daysLeft = getTrialDaysRemaining();
            return String.format("Trial - %d days remaining", daysLeft);
        }

        if (hasPromo() && isInFreePromoPhase()) {
            return "Free promotional period";
        }

        if (hasPromo() && isInDiscountPromoPhase()) {
            return "Discounted promotional period";
        }

        return status != null ? status.getDisplayName() : "Unknown";
    }

    // NEW: Sync legacy fields with new fields (call this after Stripe updates)
    public void syncLegacyFields() {
        if (currentPeriodStart != null && startDate == null) {
            startDate = currentPeriodStart;
        }
        if (currentPeriodEnd != null && renewalDate == null) {
            renewalDate = currentPeriodEnd;
        }
        if (trialEnd != null && endDate == null && status == SubscriptionStatus.TRIALING) {
            endDate = trialEnd;
        }
        if (status == SubscriptionStatus.TRIALING) {
            trial = true;
        }
    }

    // toString for debugging
    @Override
    public String toString() {
        return String.format("Subscription{id=%d, plan=%s, status=%s, business=%s, stripeId=%s}",
                id, plan, status, business != null ? business.getId() : "null", stripeSubscriptionId);
    }
}