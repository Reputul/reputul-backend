package com.reputul.backend.dto.automation;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.Map;

@Data
@Builder
public class AutomationWorkflowDto {
    private Long id;
    private String name;
    private String description;
    private String triggerType;
    private String deliveryMethod;
    private Boolean isActive;
    private Integer executionCount;
    private Map<String, Object> actions;
    private Map<String, Object> triggerConfig;
    private OffsetDateTime createdAt;
    private String createdByName;
}