package com.reputul.backend.dto;

import com.reputul.backend.models.UsageEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEventDto {

    private Long id;
    private Long businessId;
    private String businessName;
    private UsageEvent.UsageType usageType;
    private OffsetDateTime occurredAt;
    private boolean overageBilled;
    private String stripeUsageRecordId;
    private OffsetDateTime billingPeriodStart;
    private OffsetDateTime billingPeriodEnd;
    private String metadata;
}