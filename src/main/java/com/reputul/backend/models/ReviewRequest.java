package com.reputul.backend.models;

import com.reputul.backend.enums.SourceTrigger;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "review_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_template_id", nullable = false)
    private EmailTemplate emailTemplate;

    // Delivery method support
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false)
    private DeliveryMethod deliveryMethod = DeliveryMethod.EMAIL;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    // SMS recipient tracking
    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "email_body", columnDefinition = "TEXT")
    private String emailBody;

    // SMS message content
    @Column(name = "sms_message", columnDefinition = "TEXT")
    private String smsMessage;

    @Column(name = "review_link", nullable = false)
    private String reviewLink;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "opened_at")
    private OffsetDateTime openedAt;

    @Column(name = "clicked_at")
    private OffsetDateTime clickedAt;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    // NEW: Email delivery tracking
    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "error_message")
    private String errorMessage;

    // SMS-specific tracking fields
    @Column(name = "sms_message_id")
    private String smsMessageId; // Twilio SID

    @Column(name = "sms_status")
    private String smsStatus; // delivered, failed, etc.

    @Column(name = "sms_error_code")
    private String smsErrorCode;

    // NEW: Email-specific tracking fields
    @Column(name = "sendgrid_message_id")
    private String sendgridMessageId; // SendGrid message ID

    @Column(name = "email_status")
    private String emailStatus; // processed, delivered, open, click, bounce, etc.

    @Column(name = "email_error_code")
    private String emailErrorCode;

    @Column(name = "campaign_execution_id")
    private Long campaignExecutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_trigger")
    private SourceTrigger sourceTrigger = SourceTrigger.MANUAL;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // Delivery method enum
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

    public enum RequestStatus {
        PENDING,     // Created but not sent yet
        SENT,        // Successfully sent via email/SMS service
        DELIVERED,   // Confirmed delivered (webhook data)
        OPENED,      // Email was opened by recipient
        CLICKED,     // Review link was clicked
        COMPLETED,   // Review was submitted
        FAILED,      // Failed to send
        BOUNCED      // Email bounced / SMS failed
    }

    // Helper methods for delivery method checking
    public boolean isEmailDelivery() {
        return DeliveryMethod.EMAIL.equals(this.deliveryMethod);
    }

    public boolean isSmsDelivery() {
        return DeliveryMethod.SMS.equals(this.deliveryMethod);
    }

    // Helper method to get the appropriate recipient based on delivery method
    public String getRecipient() {
        return isSmsDelivery() ? recipientPhone : recipientEmail;
    }

    // Helper method to get the appropriate message content
    public String getMessageContent() {
        return isSmsDelivery() ? smsMessage : emailBody;
    }

    // NEW: Helper method to get the appropriate message ID for tracking
    public String getMessageId() {
        return isSmsDelivery() ? smsMessageId : sendgridMessageId;
    }

    // NEW: Helper method to get the appropriate status for tracking
    public String getDeliveryStatus() {
        return isSmsDelivery() ? smsStatus : emailStatus;
    }

    public Long getCampaignExecutionId() {
        return campaignExecutionId;
    }

    public void setCampaignExecutionId(Long campaignExecutionId) {
        this.campaignExecutionId = campaignExecutionId;
    }

    public SourceTrigger getSourceTrigger() {
        return sourceTrigger;
    }

    public void setSourceTrigger(SourceTrigger sourceTrigger) {
        this.sourceTrigger = sourceTrigger;
    }
}