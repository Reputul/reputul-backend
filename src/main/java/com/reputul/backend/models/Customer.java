package com.reputul.backend.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Entity
@Table(name = "customers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerStatus status = CustomerStatus.COMPLETED;

    @ElementCollection(fetch = FetchType.EAGER)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "customer_tags", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "tag")
    private List<CustomerTag> tags;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // Existing feedback tracking fields
    @Column(name = "last_feedback_date")
    private OffsetDateTime lastFeedbackDate;

    @Column(name = "feedback_count")
    @Builder.Default
    private Integer feedbackCount = 0;

    @Column(name = "feedback_submitted")
    @Builder.Default
    private Boolean feedbackSubmitted = false;

    // NEW: SMS Compliance Fields
    @Column(name = "sms_opt_in")
    @Builder.Default
    private Boolean smsOptIn = false;

    @Column(name = "sms_opt_in_method")
    @Enumerated(EnumType.STRING)
    private SmsOptInMethod smsOptInMethod;

    @Column(name = "sms_opt_in_timestamp")
    private OffsetDateTime smsOptInTimestamp;

    @Column(name = "sms_opt_in_source")
    private String smsOptInSource; // e.g., "web_form", "phone_call", "import"

    @Column(name = "sms_opt_out")
    @Builder.Default
    private Boolean smsOptOut = false;

    @Column(name = "sms_opt_out_timestamp")
    private OffsetDateTime smsOptOutTimestamp;

    @Column(name = "sms_opt_out_method")
    @Enumerated(EnumType.STRING)
    private SmsOptOutMethod smsOptOutMethod;

    @Column(name = "sms_last_sent_timestamp")
    private OffsetDateTime smsLastSentTimestamp;

    @Column(name = "sms_send_count_today")
    @Builder.Default
    private Integer smsSendCountToday = 0;

    @Column(name = "sms_send_date_reset")
    private LocalDate smsSendDateReset;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
        if (feedbackCount == null) {
            feedbackCount = 0;
        }
        if (feedbackSubmitted == null) {
            feedbackSubmitted = false;
        }
        if (smsOptIn == null) {
            smsOptIn = false;
        }
        if (smsOptOut == null) {
            smsOptOut = false;
        }
        if (smsSendCountToday == null) {
            smsSendCountToday = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // SMS Compliance Methods
    public boolean canReceiveSms() {
        return phone != null &&
                !phone.trim().isEmpty() &&
                smsOptIn != null &&
                smsOptIn &&
                (smsOptOut == null || !smsOptOut);
    }

    public void recordSmsOptIn(SmsOptInMethod method, String source) {
        this.smsOptIn = true;
        this.smsOptInMethod = method;
        this.smsOptInTimestamp = OffsetDateTime.now();
        this.smsOptInSource = source;
        // Clear any previous opt-out
        this.smsOptOut = false;
        this.smsOptOutTimestamp = null;
        this.smsOptOutMethod = null;
    }

    public void recordSmsOptOut(SmsOptOutMethod method) {
        this.smsOptOut = true;
        this.smsOptOutTimestamp = OffsetDateTime.now();
        this.smsOptOutMethod = method;
    }

    public void incrementSmsSendCount() {
        LocalDate today = LocalDate.now();

        // Reset daily counter if it's a new day
        if (smsSendDateReset == null || !smsSendDateReset.equals(today)) {
            smsSendCountToday = 0;
            smsSendDateReset = today;
        }

        smsSendCountToday++;
        smsLastSentTimestamp = OffsetDateTime.now();
    }

    public boolean hasReachedDailySmsLimit(int dailyLimit) {
        LocalDate today = LocalDate.now();

        // Reset counter if it's a new day
        if (smsSendDateReset == null || !smsSendDateReset.equals(today)) {
            return false;
        }

        return smsSendCountToday != null && smsSendCountToday >= dailyLimit;
    }

    // Enums
    public enum CustomerStatus {
        COMPLETED,
        PENDING,
        CANCELLED,
        CONTACTED
    }

    public enum CustomerTag {
        NEW_CUSTOMER,
        REPEAT_CUSTOMER,
        VIP,
        REFERRAL,
        PROBLEMATIC
    }

    public enum SmsOptInMethod {
        WEB_FORM("Web Form"),
        SMS_YES_REPLY("SMS YES Reply"),       // user replied YES to confirm (double opt-in)
        SMS_START_REPLY("SMS START Reply"),
        PHONE_CALL("Phone Call"),
        EMAIL_REPLY("Email Reply"),
        IMPORT("Data Import"),
        MANUAL("Manual Entry"),
        DOUBLE_OPT_IN("Double Opt-in");

        private final String displayName;

        SmsOptInMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum SmsOptOutMethod {
        STOP_REPLY("STOP Reply"),
        WEB_FORM("Web Form"),
        PHONE_CALL("Phone Call"),
        MANUAL("Manual Opt-out");

        private final String displayName;

        SmsOptOutMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}