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
    private Map<String, Object> settings = new HashMap<>();

    // SMS settings
    @Column(name = "sms_phone_number")
    private String smsPhoneNumber; // Dedicated phone number for SMS

    @Column(name = "sms_credits_remaining")
    @Builder.Default
    private Integer smsCreditsRemaining = 0;

    // Trial information
    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // Billing email (can be different from user emails)
    @Column(name = "billing_email")
    private String billingEmail;

    // API limits based on plan
    @Column(name = "max_businesses")
    @Builder.Default
    private Integer maxBusinesses = 1; // Solo = 1, Pro = 5, Growth = unlimited

    @Column(name = "max_users")
    @Builder.Default
    private Integer maxUsers = 1; // Solo = 1, Pro = 5, Growth = 10+

    @Column(name = "max_monthly_review_requests")
    @Builder.Default
    private Integer maxMonthlyReviewRequests = 100; // Solo = 100, Pro = 500, Growth = 2000

    // Timestamps
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // Relationships
    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<User> users = new ArrayList<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Business> businesses = new ArrayList<>();

    @OneToMany(mappedBy = "organization", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Usage> usageRecords = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Set default limits based on plan
        if ("SOLO".equals(plan)) {
            maxBusinesses = 1;
            maxUsers = 1;
            maxMonthlyReviewRequests = 100;
        } else if ("PRO".equals(plan)) {
            maxBusinesses = 5;
            maxUsers = 5;
            maxMonthlyReviewRequests = 500;
        } else if ("GROWTH".equals(plan)) {
            maxBusinesses = 999; // Effectively unlimited
            maxUsers = 999;
            maxMonthlyReviewRequests = 2000;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // Helper methods
    public boolean canAddBusiness() {
        return businesses.size() < maxBusinesses;
    }

    public boolean canAddUser() {
        return users.size() < maxUsers;
    }

    public boolean isOnTrial() {
        return trialEndsAt != null && trialEndsAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public void upgradePlan(String newPlan) {
        this.plan = newPlan;
        if ("SOLO".equals(newPlan)) {
            this.maxBusinesses = 1;
            this.maxUsers = 1;
            this.maxMonthlyReviewRequests = 100;
        } else if ("PRO".equals(newPlan)) {
            this.maxBusinesses = 5;
            this.maxUsers = 5;
            this.maxMonthlyReviewRequests = 500;
        } else if ("GROWTH".equals(newPlan)) {
            this.maxBusinesses = 999;
            this.maxUsers = 999;
            this.maxMonthlyReviewRequests = 2000;
        }
    }
}