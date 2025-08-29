package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "usage_events", indexes = {
        @Index(name = "idx_usage_business_type_date", columnList = "business_id, usage_type, occurred_at"),
        @Index(name = "idx_usage_request_id", columnList = "request_id"),
        @Index(name = "idx_usage_billing_period", columnList = "business_id, billing_period_start, billing_period_end")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false)
    private UsageType usageType;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "request_id", unique = true, nullable = false)
    private String requestId; // For idempotency (UUID)

    @Builder.Default
    @Column(name = "overage_billed", nullable = false)
    private Boolean overageBilled = false;

    @Column(name = "stripe_usage_record_id")
    private String stripeUsageRecordId; // Stripe usage record ID if billed

    // Billing period this event belongs to
    @Column(name = "billing_period_start")
    private OffsetDateTime billingPeriodStart;

    @Column(name = "billing_period_end")
    private OffsetDateTime billingPeriodEnd;

    // Additional metadata stored as JSON
    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (overageBilled == null) {
            overageBilled = false;
        }
    }

    public enum UsageType {
        SMS_REVIEW_REQUEST_SENT("SMS Review Request"),
        EMAIL_REVIEW_REQUEST_SENT("Email Review Request"),
        CUSTOMER_CREATED("Customer Created"),
        REVIEW_REQUEST_SENT("Review Request Sent"); // Generic

        private final String displayName;

        UsageType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isBillable() {
            return this == SMS_REVIEW_REQUEST_SENT;
        }
    }
}

