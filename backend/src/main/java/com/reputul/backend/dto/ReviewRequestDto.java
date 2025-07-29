package com.reputul.backend.dto;

import com.reputul.backend.models.ReviewRequest;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewRequestDto {
    private Long id;
    private Long customerId;
    private String customerName;
    private String customerEmail;
    private Long businessId;
    private String businessName;
    private Long templateId;
    private String templateName;
    private String recipientEmail;
    private String subject;
    private String reviewLink;
    private ReviewRequest.RequestStatus status;
    private LocalDateTime sentAt;
    private LocalDateTime openedAt;
    private LocalDateTime clickedAt;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private String errorMessage;
}
