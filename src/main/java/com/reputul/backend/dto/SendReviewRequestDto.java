package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendReviewRequestDto {

    private Long customerId;
    private Long templateId;
    private String deliveryMethod; // EMAIL or SMS
    private String notes;
    private String customMessage;
}