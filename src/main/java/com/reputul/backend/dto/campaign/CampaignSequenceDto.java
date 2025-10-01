package com.reputul.backend.dto.campaign;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class CampaignSequenceDto {
    private Long id;
    private Long orgId;
    private String name;
    private String description;
    private Boolean isDefault;
    private Boolean isActive;
    private Integer stepCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<CampaignStepDto> steps;
}