package com.reputul.backend.dto;

import com.reputul.backend.models.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {

    private Long id;
    private String name;
    private String email;
    private String phone;
    private LocalDate serviceDate;
    private String serviceType;
    private Customer.CustomerStatus status;
    private List<Customer.CustomerTag> tags;
    private String notes;

    // Business relationship - Updated to support nested object
    private BusinessInfo business;

    // Legacy business fields (for backward compatibility)
    private Long businessId;
    private String businessName;

    // Feedback tracking
    private Boolean feedbackSubmitted;
    private Integer feedbackCount;
    private LocalDateTime lastFeedbackDate;

    // SMS compliance fields
    private Boolean smsOptIn;
    private Customer.SmsOptInMethod smsOptInMethod;
    private LocalDateTime smsOptInTimestamp;
    private Boolean smsOptOut;
    private LocalDateTime smsOptOutTimestamp;
    private Boolean canReceiveSms;

    // Timestamps
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Nested BusinessInfo class
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessInfo {
        private Long id;
        private String name;
        private String industry;
    }
}