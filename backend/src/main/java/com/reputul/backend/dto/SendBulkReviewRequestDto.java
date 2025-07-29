package com.reputul.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendBulkReviewRequestDto {
    private List<Long> customerIds;
    private Long templateId;
    private String notes;
}