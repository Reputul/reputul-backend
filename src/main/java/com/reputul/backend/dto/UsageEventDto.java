package com.reputul.backend.dto;

import com.reputul.backend.models.UsageEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEventDto {

    private Long id;
    private Long businessId;
    private String businessName;
    private UsageEvent.UsageType usageType;
    private LocalDateTime occurredAt;
    private boolean overageBilled;
    private String stripeUsageRecordId;
    private LocalDateTime billingPeriodStart;
    private LocalDateTime billingPeriodEnd;
    private String metadata;
}