package com.reputul.backend.dto.campaign;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CampaignAnalyticsDto {
    private Long totalExecutions;
    private Long activeExecutions;
    private Long completedExecutions;
    private Long failedExecutions;
    private Double completionRate;
    private Double averageStepsCompleted;
    private List<CampaignPerformanceDto> sequencePerformance;
}
