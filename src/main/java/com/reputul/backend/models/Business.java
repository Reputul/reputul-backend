package com.reputul.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "businesses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"user", "organization", "reviews", "subscription"})
@ToString(exclude = {"user", "organization", "reviews", "subscription"})
public class Business {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String industry;
    private String phone;
    private String website;
    private String address;

    // Legacy reputation score (kept for backward compatibility)
    private Double reputationScore;

    // NEW: Enhanced Wilson Score reputation fields
    @Column(name = "reputul_rating")
    @Builder.Default
    private Double reputulRating = 0.0;

    @Column(name = "reputation_score_quality")
    @Builder.Default
    private Double reputationScoreQuality = 0.0;

    @Column(name = "reputation_score_velocity")
    @Builder.Default
    private Double reputationScoreVelocity = 0.0;

    @Column(name = "reputation_score_responsiveness")
    @Builder.Default
    private Double reputationScoreResponsiveness = 0.0;

    @Column(name = "reputation_score_composite")
    @Builder.Default
    private Double reputationScoreComposite = 0.0;

    @Column(name = "last_reputation_update")
    private OffsetDateTime lastReputationUpdate;

    @Column(name = "logo_filename")
    private String logoFilename;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "logo_content_type", length = 100)
    private String logoContentType;

    @Column(name = "logo_uploaded_at")
    private OffsetDateTime logoUploadedAt;

    private String badge;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // ===== Google Places Integration Fields =====
    private String googlePlaceId;

    @Column(name = "google_review_url", length = 500)
    private String googleReviewUrl; // Direct review URL: https://search.google.com/local/writereview?placeid=PLACE_ID

    @Column(name = "google_review_short_url", length = 300)
    private String googleReviewShortUrl; // User-provided g.page short URL: https://g.page/r/XXX/review

    @Column(name = "google_search_url", length = 500)
    private String googleSearchUrl; // Fallback search URL when Place ID unavailable

    @Column(name = "google_place_name", length = 255)
    private String googlePlaceName; // Business name from Google Places

    @Column(name = "google_place_formatted_address", length = 500)
    private String googlePlaceFormattedAddress; // Address from Google Places

    @Column(name = "google_place_types", columnDefinition = "TEXT")
    private String googlePlaceTypes; // Business types (comma-separated)

    @Column(name = "google_place_last_synced")
    private OffsetDateTime googlePlaceLastSynced; // Last sync with Google Places API

    @Column(name = "google_place_auto_detected")
    @Builder.Default
    private Boolean googlePlaceAutoDetected = false; // TRUE if auto-detected, FALSE if manual

    // ===== Other Review Platforms =====
    private String facebookPageUrl;
    private String yelpPageUrl;

    @Builder.Default
    private Boolean reviewPlatformsConfigured = false;

    @Column(name = "is_default")
    @Builder.Default
    private Boolean isDefault = false;

    // ===== EXISTING: User relationship (kept for backward compatibility) =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    // ===== ADDED: Organization relationship =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore
    private java.util.List<Review> reviews;

    @OneToOne(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore
    private Subscription subscription;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);

        // Auto-set organization from user if not explicitly set
        if (organization == null && user != null && user.getOrganization() != null) {
            organization = user.getOrganization();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ===== EXISTING: Helper methods =====
    public boolean belongsToOrganization(Long organizationId) {
        return organization != null && organization.getId().equals(organizationId);
    }

    public boolean isOwnedBy(User checkUser) {
        return user != null && user.getId().equals(checkUser.getId());
    }

    public boolean canBeAccessedBy(User checkUser) {
        // User can access if they own the business OR belong to the same organization
        if (isOwnedBy(checkUser)) {
            return true;
        }
        if (organization != null && checkUser.getOrganization() != null) {
            return organization.getId().equals(checkUser.getOrganization().getId());
        }
        return false;
    }

    // ===== NEW: Wilson Score helper methods =====

    /**
     * Get the primary public rating (Wilson Score if available, fallback to legacy)
     */
    public double getPublicRating() {
        return reputulRating != null && reputulRating > 0 ? reputulRating :
                (reputationScore != null ? Math.min(reputationScore / 20.0, 5.0) : 0.0);
    }

    /**
     * Get the owner-facing composite score (0-100)
     */
    public double getOwnerScore() {
        return reputationScoreComposite != null ? reputationScoreComposite :
                (reputationScore != null ? reputationScore : 0.0);
    }

    /**
     * Check if Wilson Score metrics have been calculated
     */
    public boolean hasWilsonScoreMetrics() {
        return reputulRating != null && reputulRating > 0 && lastReputationUpdate != null;
    }

    /**
     * Get reputation color band for UI display
     */
    public String getReputationColorBand() {
        double score = getOwnerScore();
        if (score >= 76) return "green";
        if (score >= 46) return "yellow";
        return "red";
    }

    // ===== NEW: Google Review URL helper methods =====

    /**
     * Get the best available Google review URL (priority: direct > short > search)
     */
    public String getBestGoogleReviewUrl() {
        if (googleReviewUrl != null && !googleReviewUrl.trim().isEmpty()) {
            return googleReviewUrl;
        }
        if (googleReviewShortUrl != null && !googleReviewShortUrl.trim().isEmpty()) {
            return googleReviewShortUrl;
        }
        if (googleSearchUrl != null && !googleSearchUrl.trim().isEmpty()) {
            return googleSearchUrl;
        }
        return null;
    }

    /**
     * Check if business has any Google review URL configured
     */
    public boolean hasGoogleReviewUrl() {
        return getBestGoogleReviewUrl() != null;
    }

    /**
     * Check if Google Place ID needs refresh (older than 30 days)
     */
    public boolean needsGooglePlaceRefresh() {
        if (googlePlaceLastSynced == null) return true;
        return googlePlaceLastSynced.isBefore(OffsetDateTime.now().minusDays(30));
    }

    /**
     * Check if this is the default business for the organization
     */
    public boolean isDefaultBusiness() {
        return isDefault != null && isDefault;
    }

    /**
     * Mark this business as the default for its organization
     */
    public void setAsDefault() {
        this.isDefault = true;
    }
}