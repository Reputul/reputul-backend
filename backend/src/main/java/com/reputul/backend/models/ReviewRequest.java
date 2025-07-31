package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

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

    // NEW: Delivery method support
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false)
    private DeliveryMethod deliveryMethod = DeliveryMethod.EMAIL;

    @Column(name = "recipient_email", nullable = false)
    private String recipientEmail;

    // NEW: SMS recipient tracking
    @Column(name = "recipient_phone")
    private String recipientPhone;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "email_body", columnDefinition = "TEXT")
    private String emailBody;

    // NEW: SMS message content
    @Column(name = "sms_message", columnDefinition = "TEXT")
    private String smsMessage;

    @Column(name = "review_link", nullable = false)
    private String reviewLink;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    @Column(name = "clicked_at")
    private LocalDateTime clickedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "error_message")
    private String errorMessage;

    // NEW: SMS-specific tracking fields
    @Column(name = "sms_message_id")
    private String smsMessageId; // Twilio SID

    @Column(name = "sms_status")
    private String smsStatus; // delivered, failed, etc.

    @Column(name = "sms_error_code")
    private String smsErrorCode;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // NEW: Delivery method enum
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
        DELIVERED,   // Confirmed delivered (if we have webhook data)
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
}