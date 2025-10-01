package com.reputul.backend.dto.campaign.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class StartCampaignRequest {
    @NotNull(message = "Review request ID is required")
    private Long reviewRequestId;

    private Long sequenceId; // Optional - uses default if not provided

    private Boolean overrideExisting = false; // Stop existing campaign and start new
}
