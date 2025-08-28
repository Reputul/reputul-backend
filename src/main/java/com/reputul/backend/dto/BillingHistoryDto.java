package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingHistoryDto {

    private List<InvoiceDto> invoices;
    private List<UsageEventDto> usageEvents;
    private List<SubscriptionChangeDto> subscriptionChanges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InvoiceDto {
        private String invoiceId;
        private double amount;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime paidAt;
        private String invoiceUrl;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionChangeDto {
        private String changeType;
        private String oldPlan;
        private String newPlan;
        private String oldStatus;
        private String newStatus;
        private String reason;
        private LocalDateTime changedAt;
    }
}