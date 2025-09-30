package com.reputul.backend.dto.campaign.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BulkCampaignResultDto {
    private Integer totalRequested;
    private Integer successfullyStarted;
    private Integer alreadyRunning;
    private Integer failed;
    private List<String> errorMessages;
    private List<Long> startedExecutionIds;
}
