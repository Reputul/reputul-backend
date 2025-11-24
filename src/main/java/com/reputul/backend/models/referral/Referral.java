package com.reputul.backend.models.referral;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Referral Entity
 *
 * Tracks individual referral instances - who referred whom, conversion status,
 * and reward eligibility. This is the core tracking entity for referral analytics.
 */
@Entity
@Table(name = "referrals")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Referral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_program_id", nullable = false)
    private ReferralProgram referralProgram;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_link_id", nullable = false)
    private ReferralLink referralLink;

    // Referrer (person who made the referral)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referrer_customer_id")
    private Customer referrerCustomer;

    @Column(name = "referrer_name", length = 100)
    private String referrerName;

    @Column(name = "referrer_email")
    private String referrerEmail;

    // Referee (person who was referred)
    @Column(name = "referee_name", length = 100)
    private String refereeName;

    @Column(name = "referee_email")
    private String refereeEmail;

    @Column(name = "referee_phone", length = 20)
    private String refereePhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referee_customer_id")
    private Customer refereeCustomer; // Set when they become a customer

    // Tracking Details
    @Column(name = "referral_code", length = 20)
    private String referralCode;

    @Column(name = "click_timestamp")
    private OffsetDateTime clickTimestamp;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "utm_source", length = 100)
    private String utmSource;

    @Column(name = "utm_campaign", length = 100)
    private String utmCampaign;

    // Conversion Tracking
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "status", length = 20)
    private ReferralStatus status = ReferralStatus.PENDING;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @Column(name = "purchase_amount", precision = 10, scale = 2)
    private BigDecimal purchaseAmount;

    @Column(name = "reward_issued_at")
    private OffsetDateTime rewardIssuedAt;

    @Column(name = "reward_claimed_at")
    private OffsetDateTime rewardClaimedAt;

    // Metadata stored as JSON string for JPA compatibility
    @Column(name = "metadata", columnDefinition = "JSONB")
    private String metadataJson;

    @Transient
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * Referral Status Enum
     */
    public enum ReferralStatus {
        PENDING("Pending"),
        CONVERTED("Converted"),
        REWARDED("Rewarded"),
        EXPIRED("Expired"),
        CANCELLED("Cancelled");

        private final String displayName;

        ReferralStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isActive() {
            return this == PENDING || this == CONVERTED;
        }

        public boolean isCompleted() {
            return this == CONVERTED || this == REWARDED;
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
     * Mark this referral as converted with purchase information
     */
    public void markAsConverted(BigDecimal purchaseAmount) {
        this.status = ReferralStatus.CONVERTED;
        this.convertedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.purchaseAmount = purchaseAmount;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark rewards as issued for this referral
     */
    public void markRewardsIssued() {
        this.status = ReferralStatus.REWARDED;
        this.rewardIssuedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Mark rewards as claimed
     */
    public void markRewardsClaimed() {
        this.rewardClaimedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Check if this referral is eligible for rewards
     */
    public boolean isEligibleForRewards() {
        return status == ReferralStatus.CONVERTED &&
                purchaseAmount != null &&
                (referralProgram.getMinPurchaseAmount() == null ||
                        purchaseAmount.compareTo(referralProgram.getMinPurchaseAmount()) >= 0);
    }

    /**
     * Get the time elapsed since referral click
     */
    public long getTimeSinceClick() {
        if (clickTimestamp == null) return 0;
        return java.time.Duration.between(clickTimestamp, OffsetDateTime.now(ZoneOffset.UTC)).toDays();
    }

    /**
     * Get the time to conversion (if converted)
     */
    public long getTimeToConversion() {
        if (clickTimestamp == null || convertedAt == null) return 0;
        return java.time.Duration.between(clickTimestamp, convertedAt).toDays();
    }

    /**
     * Get the primary identifier for the referee
     */
    public String getRefereeIdentifier() {
        if (refereeName != null && !refereeName.trim().isEmpty()) {
            return refereeName;
        }
        if (refereeEmail != null && !refereeEmail.trim().isEmpty()) {
            return refereeEmail;
        }
        if (refereePhone != null && !refereePhone.trim().isEmpty()) {
            return refereePhone;
        }
        return "Anonymous";
    }

    /**
     * Get the primary identifier for the referrer
     */
    public String getReferrerIdentifier() {
        if (referrerName != null && !referrerName.trim().isEmpty()) {
            return referrerName;
        }
        if (referrerEmail != null && !referrerEmail.trim().isEmpty()) {
            return referrerEmail;
        }
        return "Unknown";
    }

    /**
     * Check if this referral has expired based on program rules
     */
    public boolean hasExpired() {
        if (status.isCompleted()) return false;

        // Check program expiration
        if (referralProgram.getExpiresAt() != null &&
                OffsetDateTime.now(ZoneOffset.UTC).isAfter(referralProgram.getExpiresAt())) {
            return true;
        }

        // Check link expiration
        if (referralLink.getExpiresAt() != null &&
                OffsetDateTime.now(ZoneOffset.UTC).isAfter(referralLink.getExpiresAt())) {
            return true;
        }

        return false;
    }

    /**
     * Generate a human-readable summary of this referral
     */
    public String getSummary() {
        String referrerName = getReferrerIdentifier();
        String refereeName = getRefereeIdentifier();

        return String.format("%s referred %s - %s",
                referrerName, refereeName, status.getDisplayName());
    }

    /**
     * Calculate the value of this referral
     */
    public BigDecimal getReferralValue() {
        if (purchaseAmount == null) return BigDecimal.ZERO;
        return purchaseAmount;
    }
}