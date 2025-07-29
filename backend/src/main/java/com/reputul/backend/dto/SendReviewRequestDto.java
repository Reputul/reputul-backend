package com.reputul.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendReviewRequestDto {
    private Long customerId;
    private Long templateId;
    private String notes; // Optional notes for this specific request
}