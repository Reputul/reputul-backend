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

    // Basic customer metrics
    private Long totalCustomers;
    private Long customersWithPhone;
    private Long completedServices;
    private Long pendingServices;
    private Long cancelledServices;
    private Long repeatCustomers;
    private Long thisMonthCustomers;

    // SMS consent metrics
    private Long smsOptedIn;
    private Long smsOptedOut;
    private Long smsEligible;
    private Long needingSmsConsent;

    // Calculated percentages
    public Double getSmsOptInRate() {
        if (customersWithPhone == null || customersWithPhone == 0) {
            return 0.0;
        }
        return (smsOptedIn != null ? smsOptedIn.doubleValue() : 0.0) / customersWithPhone.doubleValue() * 100.0;
    }

    public Double getSmsEligibilityRate() {
        if (totalCustomers == null || totalCustomers == 0) {
            return 0.0;
        }
        return (smsEligible != null ? smsEligible.doubleValue() : 0.0) / totalCustomers.doubleValue() * 100.0;
    }
}