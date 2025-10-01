package com.reputul.backend.dto.campaign;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CampaignVariablesDto {
    private java.util.Map<String, String> availableVariables;
    private java.util.Set<String> requiredVariables;
    private java.util.Set<String> missingVariables;
}
