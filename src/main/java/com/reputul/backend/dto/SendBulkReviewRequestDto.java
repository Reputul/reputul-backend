package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendBulkReviewRequestDto {

    private List<Long> customerIds;
    private Long templateId;
    private String deliveryMethod; // EMAIL or SMS
    private String notes;
    private String customMessage;
}