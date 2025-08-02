package com.reputul.backend.dto;

import com.reputul.backend.models.ReviewRequest;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReviewRequestDto {
    private Long id;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone; // NEW: Customer phone number
    private Long businessId;
    private String businessName;
    private Long templateId;
    private String templateName;

    // NEW: Delivery method support
    private String deliveryMethod; // "EMAIL" or "SMS"

    private String recipientEmail;
    private String recipientPhone; // NEW: SMS recipient
    private String subject;
    private String emailBody;
    private String smsMessage; // NEW: SMS message content
    private String reviewLink;

    private ReviewRequest.RequestStatus status;
    private LocalDateTime sentAt;
    private LocalDateTime openedAt;
    private LocalDateTime clickedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private String errorMessage;

    // NEW: SMS-specific tracking fields
    private String smsMessageId; // Twilio message SID
    private String smsStatus; // SMS delivery status
    private String smsErrorCode; // SMS error code if failed

    // Helper methods for UI display
    public String getDisplayDeliveryMethod() {
        return deliveryMethod != null ? deliveryMethod : "EMAIL";
    }

    public String getDisplayRecipient() {
        if ("SMS".equals(deliveryMethod)) {
            return recipientPhone != null ? recipientPhone : customerPhone;
        }
        return recipientEmail != null ? recipientEmail : customerEmail;
    }

    public String getDisplayContent() {
        if ("SMS".equals(deliveryMethod)) {
            return smsMessage;
        }
        return emailBody;
    }

    public boolean isSmsDelivery() {
        return "SMS".equals(deliveryMethod);
    }

    public boolean isEmailDelivery() {
        return !"SMS".equals(deliveryMethod);
    }

    // Status helpers
    public boolean isPending() {
        return ReviewRequest.RequestStatus.PENDING.equals(status);
    }

    public boolean isSent() {
        return ReviewRequest.RequestStatus.SENT.equals(status);
    }

    public boolean isFailed() {
        return ReviewRequest.RequestStatus.FAILED.equals(status);
    }

    public boolean isCompleted() {
        return ReviewRequest.RequestStatus.COMPLETED.equals(status);
    }
}