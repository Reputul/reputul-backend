package com.reputul.backend.models.referral;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * ReferralLink Entity
 *
 * Represents a unique referral link/code generated for customers to share.
 * Tracks clicks, conversions, and performance for each referral link.
 */
@Entity
@Table(name = "referral_links")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReferralLink {

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
    @JoinColumn(name = "customer_id")
    private Customer customer;

    // Link Details
    @Column(name = "referral_code", length = 20, unique = true, nullable = false)
    private String referralCode; // "JOHN2024", "SMITH10", etc.

    @Column(name = "referral_url", columnDefinition = "TEXT", nullable = false)
    private String referralUrl; // Full tracking URL

    @Column(name = "short_url", length = 50)
    private String shortUrl; // Shortened version for SMS

    // Referrer Information (who's making the referral)
    @Column(name = "referrer_name", length = 100)
    private String referrerName;

    @Column(name = "referrer_email")
    private String referrerEmail;

    @Column(name = "referrer_phone", length = 20)
    private String referrerPhone;

    // Tracking
    @Builder.Default
    @Column(name = "clicks")
    private Integer clicks = 0;

    @Builder.Default
    @Column(name = "conversions")
    private Integer conversions = 0;

    @Builder.Default
    @Column(name = "total_revenue", precision = 10, scale = 2)
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    // Status
    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    // Relationships
    @OneToMany(mappedBy = "referralLink", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<Referral> referrals = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Business Logic Methods
     */

    /**
     * Check if the link is currently active and not expired
     */
    public boolean isCurrentlyActive() {
        return Boolean.TRUE.equals(isActive) &&
                (expiresAt == null || expiresAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /**
     * Record a click on this referral link
     */
    public void recordClick() {
        this.clicks = (this.clicks == null ? 0 : this.clicks) + 1;
        this.lastUsedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Calculate conversion rate
     */
    public double getConversionRate() {
        if (clicks == null || clicks == 0) return 0.0;
        int conversionCount = conversions == null ? 0 : conversions;
        return (double) conversionCount / clicks * 100.0;
    }

    /**
     * Get average revenue per conversion
     */
    public BigDecimal getAverageRevenuePerConversion() {
        if (conversions == null || conversions == 0) return BigDecimal.ZERO;
        return totalRevenue.divide(BigDecimal.valueOf(conversions), 2, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * Generate a shortened display version of the URL
     */
    public String getDisplayUrl() {
        if (shortUrl != null && !shortUrl.trim().isEmpty()) {
            return shortUrl;
        }

        if (referralUrl != null && referralUrl.length() > 50) {
            return referralUrl.substring(0, 47) + "...";
        }

        return referralUrl;
    }

    /**
     * Get the referrer's primary identifier (name, email, or phone)
     */
    public String getReferrerIdentifier() {
        if (referrerName != null && !referrerName.trim().isEmpty()) {
            return referrerName;
        }
        if (referrerEmail != null && !referrerEmail.trim().isEmpty()) {
            return referrerEmail;
        }
        if (referrerPhone != null && !referrerPhone.trim().isEmpty()) {
            return referrerPhone;
        }
        return "Unknown Referrer";
    }

    /**
     * Check if this link has had recent activity (within last 30 days)
     */
    public boolean hasRecentActivity() {
        if (lastUsedAt == null) return false;
        OffsetDateTime thirtyDaysAgo = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
        return lastUsedAt.isAfter(thirtyDaysAgo);
    }

    /**
     * Generate performance summary for analytics
     */
    public String getPerformanceSummary() {
        return String.format("%d clicks, %d conversions (%.1f%%), $%.2f revenue",
                clicks == null ? 0 : clicks,
                conversions == null ? 0 : conversions,
                getConversionRate(),
                totalRevenue == null ? 0.0 : totalRevenue.doubleValue());
    }
}