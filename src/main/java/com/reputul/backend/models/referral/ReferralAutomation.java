package com.reputul.backend.models.referral;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * ReferralAutomation Entity
 *
 * Tracks individual automation executions for referral campaigns.
 * This entity logs each time a referral invitation is sent to a customer,
 * including delivery status and tracking information.
 */
@Entity
@Table(name = "referral_automations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralAutomation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_campaign_id", nullable = false)
    private ReferralCampaign referralCampaign;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    // Execution Details
    @Column(name = "trigger_event", length = 50)
    private String triggerEvent; // 'REVIEW_SUBMITTED', 'HIGH_RATING_RECEIVED', etc.

    @Column(name = "trigger_data", columnDefinition = "JSONB")
    private String triggerDataJson; // Details about what triggered this

    @Transient
    private Map<String, Object> triggerData;

    // Scheduling
    @Column(name = "scheduled_send_at")
    private OffsetDateTime scheduledSendAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    // Message Details
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", length = 20)
    private DeliveryMethod deliveryMethod; // 'EMAIL', 'SMS'

    @Column(name = "message_content", columnDefinition = "TEXT")
    private String messageContent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_link_id")
    private ReferralLink referralLink;

    // Tracking
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", length = 20)
    private AutomationStatus status = AutomationStatus.SCHEDULED;

    @Column(name = "delivery_status", columnDefinition = "JSONB")
    private String deliveryStatusJson; // Provider-specific delivery details

    @Transient
    private Map<String, Object> deliveryStatus;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "clicked_at")
    private OffsetDateTime clickedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Delivery Method Enum
     */
    public enum DeliveryMethod {
        EMAIL("Email"),
        SMS("SMS");

        private final String displayName;

        DeliveryMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Automation Status Enum
     */
    public enum AutomationStatus {
        SCHEDULED("Scheduled"),
        SENT("Sent"),
        DELIVERED("Delivered"),
        OPENED("Opened"),
        CLICKED("Clicked"),
        FAILED("Failed"),
        CANCELLED("Cancelled");

        private final String displayName;

        AutomationStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isCompleted() {
            return this == DELIVERED || this == OPENED || this == CLICKED;
        }

        public boolean isFailed() {
            return this == FAILED || this == CANCELLED;
        }

        public boolean canRetry() {
            return this == FAILED && !this.equals(CANCELLED);
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Set default scheduled time if not provided
        if (scheduledSendAt == null) {
            scheduledSendAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Business Logic Methods
     */

    /**
     * Mark automation as sent
     */
    public void markAsSent() {
        this.status = AutomationStatus.SENT;
        this.sentAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark automation as delivered
     */
    public void markAsDelivered() {
        this.status = AutomationStatus.DELIVERED;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark automation as opened (email opened)
     */
    public void markAsOpened() {
        this.status = AutomationStatus.OPENED;
        this.openedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark automation as clicked (referral link clicked)
     */
    public void markAsClicked() {
        this.status = AutomationStatus.CLICKED;
        this.clickedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark automation as failed
     */
    public void markAsFailed(String reason) {
        this.status = AutomationStatus.FAILED;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Store failure reason in delivery status
        if (deliveryStatus == null) {
            deliveryStatus = Map.of("error", reason, "failed_at", OffsetDateTime.now(ZoneOffset.UTC).toString());
        }
    }

    /**
     * Check if automation is ready to be sent
     */
    public boolean isReadyToSend() {
        return status == AutomationStatus.SCHEDULED &&
                scheduledSendAt != null &&
                scheduledSendAt.isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Check if automation was successful
     */
    public boolean wasSuccessful() {
        return status.isCompleted();
    }

    /**
     * Get time elapsed since scheduled
     */
    public long getMinutesSinceScheduled() {
        if (scheduledSendAt == null) return 0;
        return java.time.Duration.between(scheduledSendAt, OffsetDateTime.now(ZoneOffset.UTC)).toMinutes();
    }

    /**
     * Get time elapsed since sent
     */
    public long getMinutesSinceSent() {
        if (sentAt == null) return 0;
        return java.time.Duration.between(sentAt, OffsetDateTime.now(ZoneOffset.UTC)).toMinutes();
    }

    /**
     * Get customer identifier for logging
     */
    public String getCustomerIdentifier() {
        if (customer == null) return "Unknown";
        return customer.getName() != null ? customer.getName() : customer.getEmail();
    }

    /**
     * Get campaign name for logging
     */
    public String getCampaignName() {
        if (referralCampaign == null) return "Unknown Campaign";
        return referralCampaign.getName();
    }

    /**
     * Generate execution summary for analytics
     */
    public String getExecutionSummary() {
        return String.format("%s sent via %s to %s - %s",
                getCampaignName(),
                deliveryMethod != null ? deliveryMethod.getDisplayName() : "Unknown",
                getCustomerIdentifier(),
                status.getDisplayName());
    }

    /**
     * Check if automation can be retried
     */
    public boolean canRetry() {
        return status.canRetry() && getMinutesSinceScheduled() < 1440; // Within 24 hours
    }
}