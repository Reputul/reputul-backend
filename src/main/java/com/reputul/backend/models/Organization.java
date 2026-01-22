package com.reputul.backend.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Organization (Workspace) entity for multi-tenancy
 * Each organization can have multiple users and businesses
 */
@Entity
@Table(name = "organizations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"users", "businesses", "usageRecords"})
@ToString(exclude = {"users", "businesses", "usageRecords"})
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Organization name is required")
    @Column(nullable = false)
    private String name;

    // Subscription plan: SOLO, PRO, GROWTH
    @Column(name = "plan", nullable = false)
    @Builder.Default
    private String plan = "SOLO";

    // Stripe customer ID for billing
    @Column(name = "stripe_customer_id", unique = true)
    private String stripeCustomerId;

    // Stripe subscription ID
    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> settings = new HashMap<>();

    // === Beta Tester Fields (ADDED) ===

    /**
     * Flag indicating if this organization is a beta tester
     * Beta testers get extended free access (90 days default)
     */
    @Column(name = "beta_tester")
    @Builder.Default
    private Boolean betaTester = false;

    /**
     * When the beta tester access expires
     * If null and betaTester=true, access never expires
     */
    @Column(name = "beta_expires_at")
    private OffsetDateTime betaExpiresAt;

    // === SMS Usage Tracking (UPDATED - No longer metered, now included in plans) ===

    /**
     * SMS phone number for this organization
     */
    @Column(name = "sms_phone_number")
    private String smsPhoneNumber;

    /**
     * Maximum SMS messages allowed per month based on plan
     * Solo: 100, Pro: 300, Growth: 1000
     */
    @Column(name = "sms_limit_monthly")
    private Integer smsLimitMonthly;

    /**
     * Number of SMS messages used in current billing period
     */
    @Column(name = "sms_used_this_month")
    @Builder.Default
    private Integer smsUsedThisMonth = 0;

    /**
     * Start of current SMS billing period (resets monthly)
     */
    @Column(name = "sms_period_start")
    private OffsetDateTime smsPeriodStart;

    // === Review Request Usage Tracking ===

    /**
     * Number of review requests sent in current billing period
     */
    @Column(name = "review_requests_used_this_month")
    @Builder.Default
    private Integer reviewRequestsUsedThisMonth = 0;

    // === Trial Information ===

    /**
     * When the trial period ends
     * For beta testers, this is set to betaExpiresAt
     */
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    // === Organization Status ===

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // === Billing Information ===

    /**
     * Billing email (can be different from user emails)
     */
    @Column(name = "billing_email")
    private String billingEmail;

    // === Plan Limits ===

    /**
     * Maximum businesses allowed based on plan
     * Solo: 1, Pro: 3, Growth: 10
     */
    @Column(name = "max_businesses")
    @Builder.Default
    private Integer maxBusinesses = 1;

    /**
     * Maximum users allowed based on plan
     * Solo: 1, Pro: 5, Growth: 10+
     */
    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 1;

    /**
     * Maximum monthly review requests based on plan
     * Solo: 100, Pro: 500, Growth: 2000
     */
    @Column(name = "max_monthly_review_requests")
    @Builder.Default
    private Integer maxMonthlyReviewRequests = 100;

    // === Timestamps ===

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // === Relationships ===

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<User> users = new ArrayList<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Business> businesses = new ArrayList<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Usage> usageRecords = new ArrayList<>();

    // === Lifecycle Callbacks ===

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Set default plan limits if not set
        if (smsLimitMonthly == null) {
            smsLimitMonthly = getSmsLimitForPlan(plan);
        }
        if (maxMonthlyReviewRequests == null) {
            maxMonthlyReviewRequests = getReviewRequestLimitForPlan(plan);
        }
        if (maxBusinesses == null) {
            maxBusinesses = getBusinessLimitForPlan(plan);
        }
        if (maxUsers == null) {
            maxUsers = getUserLimitForPlan(plan);
        }

        // Initialize SMS period start
        if (smsPeriodStart == null) {
            smsPeriodStart = OffsetDateTime.now(ZoneOffset.UTC);
        }

        // Set 14-day trial for non-beta users
        if (!Boolean.TRUE.equals(betaTester) && trialEndsAt == null) {
            trialEndsAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(14);
        }

        // For beta testers, set trial end to beta expiration
        if (Boolean.TRUE.equals(betaTester) && betaExpiresAt != null && trialEndsAt == null) {
            trialEndsAt = betaExpiresAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // === Helper Methods ===

    /**
     * Check if organization has active trial or beta access
     */
    public boolean hasActiveAccess() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Beta testers with no expiration always have access
        if (Boolean.TRUE.equals(betaTester) && betaExpiresAt == null) {
            return true;
        }

        // Beta testers with future expiration
        if (Boolean.TRUE.equals(betaTester) && betaExpiresAt != null && betaExpiresAt.isAfter(now)) {
            return true;
        }

        // Regular trial
        if (trialEndsAt != null && trialEndsAt.isAfter(now)) {
            return true;
        }

        // Has active Stripe subscription
        if (stripeSubscriptionId != null) {
            return true;
        }

        return false;
    }

    /**
     * Check if user can send SMS (within monthly limit)
     */
    public boolean canSendSms() {
        if (smsLimitMonthly == null || smsUsedThisMonth == null) {
            return false;
        }

        // Beta testers with unlimited access
        if (Boolean.TRUE.equals(betaTester)) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (betaExpiresAt == null || betaExpiresAt.isAfter(now)) {
                return true; // Unlimited for active beta testers
            }
        }

        return smsUsedThisMonth < smsLimitMonthly;
    }

    /**
     * Increment SMS usage counter
     */
    public void incrementSmsUsage() {
        if (smsUsedThisMonth == null) {
            smsUsedThisMonth = 0;
        }
        smsUsedThisMonth++;
    }

    /**
     * Reset monthly usage counters (called at beginning of each billing period)
     */
    public void resetMonthlyUsage() {
        smsUsedThisMonth = 0;
        reviewRequestsUsedThisMonth = 0;
        smsPeriodStart = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Get SMS limit based on plan type
     */
    private static Integer getSmsLimitForPlan(String plan) {
        return switch (plan) {
            case "SOLO" -> 100;
            case "PRO" -> 300;
            case "GROWTH" -> 1000;
            default -> 100;
        };
    }

    /**
     * Get review request limit based on plan type
     */
    private static Integer getReviewRequestLimitForPlan(String plan) {
        return switch (plan) {
            case "SOLO" -> 100;
            case "PRO" -> 500;
            case "GROWTH" -> 2000;
            default -> 100;
        };
    }

    /**
     * Get business limit based on plan type
     */
    private static Integer getBusinessLimitForPlan(String plan) {
        return switch (plan) {
            case "SOLO" -> 1;
            case "PRO" -> 3;
            case "GROWTH" -> 10;
            default -> 1;
        };
    }

    /**
     * Get user limit based on plan type
     */
    private static Integer getUserLimitForPlan(String plan) {
        return switch (plan) {
            case "SOLO" -> 1;
            case "PRO" -> 5;
            case "GROWTH" -> 20;
            default -> 1;
        };
    }

    /**
     * Update plan limits when plan changes
     */
    public void updatePlanLimits(String newPlan) {
        this.plan = newPlan;
        this.smsLimitMonthly = getSmsLimitForPlan(newPlan);
        this.maxMonthlyReviewRequests = getReviewRequestLimitForPlan(newPlan);
        this.maxBusinesses = getBusinessLimitForPlan(newPlan);
        this.maxUsers = getUserLimitForPlan(newPlan);
    }
}