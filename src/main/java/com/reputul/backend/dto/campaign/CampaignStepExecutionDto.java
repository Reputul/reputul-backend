package com.reputul.backend.dto.campaign;

import com.reputul.backend.enums.MessageType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class CampaignStepExecutionDto {
    private Long id;
    private Long executionId;
    private Long stepId;
    private Integer stepNumber;
    private MessageType messageType;
    private OffsetDateTime scheduledAt;
    private OffsetDateTime sentAt;
    private String status;
    private String errorMessage;
    private String delayDescription;
    private Boolean isDue;
}

