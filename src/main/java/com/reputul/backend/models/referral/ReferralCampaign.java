package com.reputul.backend.models.referral;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReferralCampaign Entity
 *
 * Represents an automated campaign that sends referral invitations to customers
 * based on triggers like positive reviews or completed services.
 * Integrates with the existing automation engine.
 */
@Entity
@Table(name = "referral_campaigns")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_program_id", nullable = false)
    private ReferralProgram referralProgram;

    // Campaign Configuration
    @Column(length = 100, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    // Trigger Configuration
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "trigger_type", length = 30)
    private TriggerType triggerType = TriggerType.REVIEW_SUBMITTED;

    @Column(name = "trigger_conditions", columnDefinition = "JSONB")
    private String triggerConditionsJson; // {"min_rating": 4, "review_platforms": ["google", "facebook"]}

    @Transient
    private Map<String, Object> triggerConditions;

    // Campaign Settings
    @Builder.Default
    @Column(name = "delay_hours")
    private Integer delayHours = 24; // Wait time before sending referral invite

    @Builder.Default
    @Column(name = "max_sends_per_customer")
    private Integer maxSendsPerCustomer = 3; // Don't spam customers

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "send_method", length = 20)
    private SendMethod sendMethod = SendMethod.EMAIL;

    // Email Template
    @Column(name = "email_subject", length = 200)
    private String emailSubject;

    @Column(name = "email_template", columnDefinition = "TEXT")
    private String emailTemplate;

    // SMS Template
    @Column(name = "sms_template", columnDefinition = "TEXT")
    private String smsTemplate;

    // Performance Tracking
    @Builder.Default
    @Column(name = "total_sent")
    private Integer totalSent = 0;

    @Builder.Default
    @Column(name = "total_clicks")
    private Integer totalClicks = 0;

    @Builder.Default
    @Column(name = "total_conversions")
    private Integer totalConversions = 0;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // Relationships
    @OneToMany(mappedBy = "referralCampaign", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReferralAutomation> automations = new ArrayList<>();

    /**
     * Trigger Type Enum - when to send referral invitations
     */
    public enum TriggerType {
        REVIEW_SUBMITTED("After Review Submitted"),
        SERVICE_COMPLETED("After Service Completed"),
        POSITIVE_FEEDBACK("After Positive Feedback"),
        HIGH_RATING("After High Rating"),
        MANUAL("Manual Send");

        private final String displayName;

        TriggerType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Send Method Enum
     */
    public enum SendMethod {
        EMAIL("Email Only"),
        SMS("SMS Only"),
        BOTH("Email & SMS");

        private final String displayName;

        SendMethod(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean includesEmail() {
            return this == EMAIL || this == BOTH;
        }

        public boolean includesSms() {
            return this == SMS || this == BOTH;
        }
    }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Business Logic Methods
     */

    /**
     * Check if the campaign is currently active
     */
    public boolean isCurrentlyActive() {
        return Boolean.TRUE.equals(isActive) && referralProgram.isCurrentlyActive();
    }

    /**
     * Calculate click-through rate
     */
    public double getClickThroughRate() {
        if (totalSent == null || totalSent == 0) return 0.0;
        int clicks = totalClicks == null ? 0 : totalClicks;
        return (double) clicks / totalSent * 100.0;
    }

    /**
     * Calculate conversion rate
     */
    public double getConversionRate() {
        if (totalClicks == null || totalClicks == 0) return 0.0;
        int conversions = totalConversions == null ? 0 : totalConversions;
        return (double) conversions / totalClicks * 100.0;
    }

    /**
     * Calculate overall campaign conversion rate (sent to conversion)
     */
    public double getOverallConversionRate() {
        if (totalSent == null || totalSent == 0) return 0.0;
        int conversions = totalConversions == null ? 0 : totalConversions;
        return (double) conversions / totalSent * 100.0;
    }

    /**
     * Get default email template if none is set
     */
    public String getEffectiveEmailTemplate() {
        if (emailTemplate != null && !emailTemplate.trim().isEmpty()) {
            return emailTemplate;
        }

        return generateDefaultEmailTemplate();
    }

    /**
     * Get default SMS template if none is set
     */
    public String getEffectiveSmsTemplate() {
        if (smsTemplate != null && !smsTemplate.trim().isEmpty()) {
            return smsTemplate;
        }

        return generateDefaultSmsTemplate();
    }

    /**
     * Get default email subject if none is set
     */
    public String getEffectiveEmailSubject() {
        if (emailSubject != null && !emailSubject.trim().isEmpty()) {
            return emailSubject;
        }

        return "Share the love and save with " + (business != null ? business.getName() : "us") + "!";
    }

    /**
     * Generate default email template
     */
    private String generateDefaultEmailTemplate() {
        String businessName = business != null ? business.getName() : "us";
        String rewardDesc = referralProgram.getFormattedRewardDescription();
        String referrerRewardDesc = referralProgram.getFormattedReferrerRewardDescription();

        return String.format(
                "Hi {{customer_name}},\n\n" +
                        "Thank you for choosing %s! We hope you loved our service.\n\n" +
                        "Want to share the love? Refer a friend and you'll both save!\n\n" +
                        "🎁 Your friend gets: %s\n" +
                        "🎁 You get: %s\n\n" +
                        "Share your unique link: {{referral_url}}\n\n" +
                        "Thanks for spreading the word!\n\n" +
                        "The %s Team",
                businessName, rewardDesc, referrerRewardDesc, businessName
        );
    }

    /**
     * Generate default SMS template
     */
    private String generateDefaultSmsTemplate() {
        String businessName = business != null ? business.getName() : "us";
        String rewardDesc = referralProgram.getFormattedRewardDescription();

        return String.format(
                "Hi {{customer_name}}! Loved our service? Refer a friend and you both get %s! " +
                        "Share: {{referral_url}} - %s",
                rewardDesc, businessName
        );
    }

    /**
     * Check if a customer can receive more campaign messages
     */
    public boolean canSendToCustomer(int previousSentCount) {
        return maxSendsPerCustomer == null || previousSentCount < maxSendsPerCustomer;
    }

    /**
     * Get campaign performance summary
     */
    public String getPerformanceSummary() {
        return String.format("Sent: %d, Clicks: %d (%.1f%%), Conversions: %d (%.1f%%)",
                totalSent == null ? 0 : totalSent,
                totalClicks == null ? 0 : totalClicks,
                getClickThroughRate(),
                totalConversions == null ? 0 : totalConversions,
                getOverallConversionRate());
    }
}