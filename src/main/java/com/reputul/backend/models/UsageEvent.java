package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

import lombok.extern.slf4j.Slf4j;

/**
 * Entity to track usage events for billing and analytics
 * Used for metered billing integration with Stripe
 */
@Entity
@Table(name = "usage_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class UsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Business relationship
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    // Type of usage event
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UsageType type;

    // Reference to the entity that triggered this usage (optional)
    @Column(name = "reference_id")
    private String referenceId;

    // Quantity for this usage event (default 1)
    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    // Whether this usage was billed as overage to Stripe
    @Column(name = "overage_billed", nullable = false)
    @Builder.Default
    private Boolean overageBilled = false;

    // Stripe usage record ID (if billed)
    @Column(name = "stripe_usage_record_id")
    private String stripeUsageRecordId;

    // Metadata for additional context (JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    // Audit timestamp
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * Types of usage events we track
     */
    public enum UsageType {
        // Communication events
        SMS_REVIEW_REQUEST_SENT("SMS Review Request Sent"),
        EMAIL_REVIEW_REQUEST_SENT("Email Review Request Sent"),

        // General events
        REVIEW_REQUEST_SENT("Review Request Sent"),
        CUSTOMER_CREATED("Customer Created"),

        // Business events
        REVIEW_IMPORTED("Review Imported"),
        REPUTATION_SCORE_CALCULATED("Reputation Score Calculated"),

        // Integration events
        GOOGLE_REVIEW_SYNC("Google Review Sync"),
        TWILIO_SMS_SENT("Twilio SMS Sent"),
        SENDGRID_EMAIL_SENT("SendGrid Email Sent");

        private final String displayName;

        UsageType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /**
         * Check if this usage type should trigger billing
         */
        public boolean isBillable() {
            return this == SMS_REVIEW_REQUEST_SENT ||
                    this == TWILIO_SMS_SENT;
        }

        /**
         * Check if this usage type counts towards daily limits
         */
        public boolean countsTowardsDailyLimit() {
            return this == REVIEW_REQUEST_SENT ||
                    this == SMS_REVIEW_REQUEST_SENT ||
                    this == EMAIL_REVIEW_REQUEST_SENT;
        }

        /**
         * Check if this usage type counts towards monthly limits
         */
        public boolean countsTowardsMonthlyLimit() {
            return this == SMS_REVIEW_REQUEST_SENT ||
                    this == EMAIL_REVIEW_REQUEST_SENT;
        }
    }

    // Helper methods

    /**
     * Check if this usage event was billed to Stripe
     */
    public boolean wasBilledToStripe() {
        return Boolean.TRUE.equals(overageBilled) &&
                stripeUsageRecordId != null &&
                !stripeUsageRecordId.trim().isEmpty();
    }

    /**
     * Check if this is a billable event type
     */
    public boolean isBillableEvent() {
        return type != null && type.isBillable();
    }

    /**
     * Get human-readable description of this usage event
     */
    public String getDescription() {
        if (type == null) return "Unknown usage event";

        String baseDescription = type.getDisplayName();
        if (quantity != null && quantity > 1) {
            baseDescription += String.format(" (x%d)", quantity);
        }

        if (Boolean.TRUE.equals(overageBilled)) {
            baseDescription += " [Overage Billed]";
        }

        return baseDescription;
    }

    /**
     * Create metadata for this usage event
     */
    public void addMetadata(String key, Object value) {
        // Simple implementation - in production you might use a proper JSON library
        if (metadata == null) {
            metadata = "{}";
        }
        // This is a simplified approach - use Jackson ObjectMapper in real implementation
        log.debug("Adding metadata {}: {} to usage event {}", key, value, id);
    }

    // toString for debugging
    @Override
    public String toString() {
        return String.format("UsageEvent{id=%d, type=%s, business=%s, quantity=%d, overage=%s}",
                id, type, business != null ? business.getId() : "null", quantity, overageBilled);
    }
}