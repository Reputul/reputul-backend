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
    private String googlePlaceId;
    private String facebookPageUrl;
    private String yelpPageUrl;

    @Builder.Default
    private Boolean reviewPlatformsConfigured = false;

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
}