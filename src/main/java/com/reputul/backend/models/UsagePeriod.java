package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// Separate entity for aggregated usage tracking per billing period
@Entity
@Table(name = "usage_periods", indexes = {
        @Index(name = "idx_usage_period_business", columnList = "business_id, period_start, period_end")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
class UsagePeriod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    // Usage counters
    @Builder.Default
    @Column(name = "sms_sent", nullable = false)
    private Integer smsSent = 0;

    @Builder.Default
    @Column(name = "sms_overage", nullable = false)
    private Integer smsOverage = 0;

    @Builder.Default
    @Column(name = "email_sent", nullable = false)
    private Integer emailSent = 0;

    @Builder.Default
    @Column(name = "customers_created", nullable = false)
    private Integer customersCreated = 0;

    @Builder.Default
    @Column(name = "requests_sent_today", nullable = false)
    private Integer requestsSentToday = 0;

    @Column(name = "last_reset_date")
    private LocalDateTime lastResetDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        initializeCounters();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    private void initializeCounters() {
        if (smsSent == null) smsSent = 0;
        if (smsOverage == null) smsOverage = 0;
        if (emailSent == null) emailSent = 0;
        if (customersCreated == null) customersCreated = 0;
        if (requestsSentToday == null) requestsSentToday = 0;
    }

    public void incrementSms(boolean isOverage) {
        smsSent++;
        if (isOverage) {
            smsOverage++;
        }
    }

    public void incrementEmail() {
        emailSent++;
    }

    public void incrementCustomer() {
        customersCreated++;
    }

    public void incrementDailyRequests() {
        requestsSentToday++;
    }

    public void resetDailyCounters() {
        requestsSentToday = 0;
        lastResetDate = LocalDateTime.now();
    }
}
