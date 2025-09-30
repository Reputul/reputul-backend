package com.reputul.backend.dto.campaign.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BulkCampaignRequest {
    @NotNull(message = "Review request IDs are required")
    private List<Long> reviewRequestIds;

    private Long sequenceId; // Optional - uses default if not provided

    private Boolean overrideExisting = false;
}
