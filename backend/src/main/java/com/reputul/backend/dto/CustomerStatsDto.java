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
    private long totalCustomers;
    private long completedServices;
    private long repeatCustomers;
    private long thisMonthCustomers;
    private long pendingServices;
    private long cancelledServices;
}