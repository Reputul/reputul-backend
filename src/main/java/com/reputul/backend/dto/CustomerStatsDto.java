package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerStatsDto {

    // Basic counts (changed to long to match repository return types)
    private long totalCustomers;
    private long completedServices;
    private long pendingServices;
    private long cancelledServices;
    private long repeatCustomers;
    private int thisMonthCustomers; // int since it's from .size()

    // Plan limits
    private Integer maxCustomers;
    private boolean atLimit;
    private boolean canAddMore;
    private String currentPlan;

    // Usage breakdown
    private int newThisMonth;
    private int activeCustomers;
    private int smsOptedIn;
    private int emailOptedIn;

    // Plan upgrade suggestion
    private String upgradeMessage;
    private String upgradeUrl;
}