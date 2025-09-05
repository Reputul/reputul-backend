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
    private Double reputationScore;
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

    // ===== ADDED: Helper methods =====
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
}