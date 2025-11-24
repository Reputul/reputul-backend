package com.reputul.backend.models.referral;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * ReferralProgram Entity
 *
 * Represents a referral program configuration for an organization or specific business.
 * Defines the reward structure, limits, and tracking for customer referrals.
 */
@Entity
@Table(name = "referral_programs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business; // NULL = organization-wide program

    // Program Configuration
    @Column(length = 100, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    // Reward Settings
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "reward_type", length = 20)
    private RewardType rewardType = RewardType.DISCOUNT;

    @Column(name = "reward_amount", precision = 10, scale = 2)
    private BigDecimal rewardAmount; // Dollar amount for cash/credit/gift cards

    @Column(name = "reward_percentage")
    private Integer rewardPercentage; // Percentage for discounts (5 = 5%)

    @Column(name = "reward_description")
    private String rewardDescription; // "10% off next service", "$25 gift card", etc.

    // Referrer Rewards (person making the referral)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(name = "referrer_reward_type", length = 20)
    private RewardType referrerRewardType = RewardType.DISCOUNT;

    @Column(name = "referrer_reward_amount", precision = 10, scale = 2)
    private BigDecimal referrerRewardAmount;

    @Column(name = "referrer_reward_percentage")
    private Integer referrerRewardPercentage;

    @Column(name = "referrer_reward_description")
    private String referrerRewardDescription;

    // Program Limits
    @Column(name = "max_referrals_per_customer")
    private Integer maxReferralsPerCustomer; // NULL = unlimited

    @Column(name = "max_program_redemptions")
    private Integer maxProgramRedemptions; // NULL = unlimited

    @Column(name = "min_purchase_amount", precision = 10, scale = 2)
    private BigDecimal minPurchaseAmount; // Minimum spend to qualify

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    // Tracking
    @Builder.Default
    @Column(name = "total_referrals")
    private Integer totalReferrals = 0;

    @Builder.Default
    @Column(name = "total_conversions")
    private Integer totalConversions = 0;

    @Builder.Default
    @Column(name = "total_revenue", precision = 10, scale = 2)
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // Relationships
    @OneToMany(mappedBy = "referralProgram", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReferralLink> referralLinks = new ArrayList<>();

    @OneToMany(mappedBy = "referralProgram", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Referral> referrals = new ArrayList<>();

    @OneToMany(mappedBy = "referralProgram", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ReferralCampaign> campaigns = new ArrayList<>();

    /**
     * Reward Type Enum
     */
    public enum RewardType {
        DISCOUNT("Discount"),
        CASH("Cash"),
        CREDIT("Store Credit"),
        GIFT_CARD("Gift Card"),
        SERVICE("Free Service");

        private final String displayName;

        RewardType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
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
     * Check if the program is currently active and not expired
     */
    public boolean isCurrentlyActive() {
        return Boolean.TRUE.equals(isActive) &&
                (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Check if the program has reached its maximum redemptions
     */
    public boolean hasReachedMaxRedemptions() {
        return maxProgramRedemptions != null && totalConversions >= maxProgramRedemptions;
    }

    /**
     * Get the formatted reward description for referees
     */
    public String getFormattedRewardDescription() {
        if (rewardDescription != null && !rewardDescription.trim().isEmpty()) {
            return rewardDescription;
        }

        switch (rewardType) {
            case DISCOUNT:
                return rewardPercentage + "% off your next service";
            case CASH:
                return "$" + rewardAmount + " cash reward";
            case CREDIT:
                return "$" + rewardAmount + " store credit";
            case GIFT_CARD:
                return "$" + rewardAmount + " gift card";
            case SERVICE:
                return "Free service";
            default:
                return "Special reward";
        }
    }

    /**
     * Get the formatted reward description for referrers
     */
    public String getFormattedReferrerRewardDescription() {
        if (referrerRewardDescription != null && !referrerRewardDescription.trim().isEmpty()) {
            return referrerRewardDescription;
        }

        switch (referrerRewardType) {
            case DISCOUNT:
                return referrerRewardPercentage + "% off for referring a friend";
            case CASH:
                return "$" + referrerRewardAmount + " for each referral";
            case CREDIT:
                return "$" + referrerRewardAmount + " credit for each referral";
            case GIFT_CARD:
                return "$" + referrerRewardAmount + " gift card for each referral";
            case SERVICE:
                return "Free service for referring a friend";
            default:
                return "Referral reward";
        }
    }

    /**
     * Calculate conversion rate
     */
    public double getConversionRate() {
        if (totalReferrals == 0) return 0.0;
        return (double) totalConversions / totalReferrals * 100.0;
    }

    /**
     * Get average revenue per conversion
     */
    public BigDecimal getAverageRevenuePerConversion() {
        if (totalConversions == 0) return BigDecimal.ZERO;
        return totalRevenue.divide(BigDecimal.valueOf(totalConversions), 2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * Check if a customer can still make referrals under this program
     */
    public boolean canCustomerMakeMoreReferrals(int currentReferralCount) {
        return maxReferralsPerCustomer == null || currentReferralCount < maxReferralsPerCustomer;
    }
}