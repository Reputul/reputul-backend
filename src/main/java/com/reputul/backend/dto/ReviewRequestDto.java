package com.reputul.backend.dto;

import com.reputul.backend.models.ReviewRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequestDto {

    private Long id;

    // Customer info
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    // Business info
    private Long businessId;
    private String businessName;

    // Template info - ADDED MISSING FIELDS
    private Long templateId;
    private String templateName;

    // Delivery details
    private String deliveryMethod;
    private String recipientEmail;
    private String recipientPhone;

    // Content - ADDED MISSING FIELDS
    private String subject;
    private String emailBody;
    private String smsMessage;
    private String reviewLink;

    // Status and tracking
    private ReviewRequest.RequestStatus status;
    private OffsetDateTime sentAt;
    private OffsetDateTime openedAt;
    private OffsetDateTime clickedAt;
    private OffsetDateTime reviewedAt;

    // Error handling
    private String errorMessage;

    // SMS specific
    private String smsMessageId;
    private String smsStatus;

    // Timestamps
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}