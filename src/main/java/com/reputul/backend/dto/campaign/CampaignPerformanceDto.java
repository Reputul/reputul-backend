package com.reputul.backend.dto.campaign;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CampaignPerformanceDto {
    private Long sequenceId;
    private String sequenceName;
    private Long totalExecutions;
    private Long completedExecutions;
    private Double completionRate;
    private Double averageCompletionTime; // in hours
    private MessageTypePerformanceDto smsPerformance;
    private MessageTypePerformanceDto emailPerformance;
}
