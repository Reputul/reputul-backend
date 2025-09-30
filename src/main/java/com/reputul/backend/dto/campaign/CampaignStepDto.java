package com.reputul.backend.dto.campaign;

import com.reputul.backend.enums.ExecutionStatus;
import com.reputul.backend.enums.MessageType;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class CampaignStepDto {
    private Long id;
    private Long sequenceId;
    private Integer stepNumber;
    private Integer delayHours;
    private MessageType messageType;
    private String subjectTemplate;
    private String bodyTemplate;
    private Boolean isActive;
    private String delayDescription;
    private LocalDateTime createdAt;
}