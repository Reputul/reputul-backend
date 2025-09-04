package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Usage tracking entity for billing and analytics
 * Tracks email, SMS, and other billable usage per organization
 */
@Entity
@Table(name = "usage_tracking")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"organization"})
@ToString(exclude = {"organization"})
public class Usage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Metric metric;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    // Additional context about the usage
    @Column(name = "metadata")
    private String metadata; // E.g., "REVIEW_REQUEST", "FOLLOW_UP", etc.

    // Reference to the related entity (optional)
    @Column(name = "reference_type")
    private String referenceType; // E.g., "ReviewRequest", "Customer"

    @Column(name = "reference_id")
    private Long referenceId;

    // Billing period this usage belongs to
    @Column(name = "period_start")
    private OffsetDateTime periodStart;

    @Column(name = "period_end")
    private OffsetDateTime periodEnd;

    // Whether this usage has been billed
    @Column(name = "billed")
    @Builder.Default
    private Boolean billed = false;

    // Stripe usage record ID (if reported to Stripe)
    @Column(name = "stripe_usage_record_id")
    private String stripeUsageRecordId;

    // Cost calculation (in cents)
    @Column(name = "cost_cents")
    private Integer costCents;

    // Timestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public enum Metric {
        // Email metrics
        EMAIL_SENT("email_sent", "Email Sent", 0), // Included in plan

        // SMS metrics (usage-based billing)
        SMS_SENT("sms_sent", "SMS Message Sent", 15), // 15 cents per SMS

        // Review request metrics
        REVIEW_REQUEST_SENT("review_request", "Review Request Sent", 0), // Included in plan
        REVIEW_REQUEST_OPENED("review_opened", "Review Request Opened", 0),
        REVIEW_REQUEST_COMPLETED("review_completed", "Review Completed", 0),

        // Integration metrics
        GOOGLE_REVIEW_IMPORT("google_import", "Google Review Imported", 0),
        FACEBOOK_REVIEW_IMPORT("facebook_import", "Facebook Review Imported", 0),

        // QR code metrics
        QR_CODE_GENERATED("qr_generated", "QR Code Generated", 0),
        QR_CODE_SCANNED("qr_scanned", "QR Code Scanned", 0),

        // Widget metrics
        WIDGET_IMPRESSION("widget_impression", "Widget Impression", 0),
        WIDGET_INTERACTION("widget_interaction", "Widget Interaction", 0);

        private final String code;
        private final String displayName;
        private final int defaultCostCents;

        Metric(String code, String displayName, int defaultCostCents) {
            this.code = code;
            this.displayName = displayName;
            this.defaultCostCents = defaultCostCents;
        }

        public String getCode() {
            return code;
        }

        public String getDisplayName() {
            return displayName;
        }

        public int getDefaultCostCents() {
            return defaultCostCents;
        }
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }

        // Set period to current time if not specified
        if (periodStart == null) {
            periodStart = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (periodEnd == null) {
            periodEnd = periodStart;
        }

        // Calculate cost if not set
        if (costCents == null && metric != null) {
            costCents = metric.getDefaultCostCents() * quantity;
        }
    }

    // Helper methods
    public boolean isChargeable() {
        return metric != null && metric.getDefaultCostCents() > 0;
    }

    public boolean isSmsUsage() {
        return Metric.SMS_SENT.equals(metric);
    }

    public boolean isEmailUsage() {
        return Metric.EMAIL_SENT.equals(metric);
    }
}