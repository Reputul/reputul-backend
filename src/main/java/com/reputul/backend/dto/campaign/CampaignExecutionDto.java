package com.reputul.backend.dto.campaign;

import com.reputul.backend.enums.ExecutionStatus;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CampaignExecutionDto {
    private Long id;
    private Long reviewRequestId;
    private Long sequenceId;
    private String sequenceName;
    private Integer currentStep;
    private ExecutionStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<CampaignStepExecutionDto> stepExecutions;

    // Customer and business info for display
    private String customerName;
    private String customerEmail;
    private String businessName;
}