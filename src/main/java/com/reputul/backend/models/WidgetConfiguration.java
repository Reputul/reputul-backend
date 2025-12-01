package com.reputul.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Widget Configuration Entity
 *
 * Stores settings for embeddable social proof widgets that businesses
 * can add to their websites to showcase reviews and ratings.
 *
 * Widget Types:
 * - POPUP: Floating notifications showing recent reviews
 * - BADGE: Compact rating badge for headers/footers
 * - CAROUSEL: Scrolling review carousel
 * - GRID: Full review grid/wall for testimonial pages
 */
@Entity
@Table(name = "widget_configurations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(exclude = {"business", "organization"})
@ToString(exclude = {"business", "organization"})
public class WidgetConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ===== Relationships =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    @JsonIgnore
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    // ===== Widget Identity =====
    @Column(name = "widget_key", unique = true, nullable = false, length = 64)
    private String widgetKey;

    @Column(name = "widget_type", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private WidgetType widgetType;

    @Column(name = "name", length = 100)
    private String name;

    // ===== Display Settings =====
    @Column(name = "theme", length = 20)
    @Builder.Default
    private String theme = "light";

    @Column(name = "primary_color", length = 7)
    @Builder.Default
    private String primaryColor = "#3B82F6";

    @Column(name = "accent_color", length = 7)
    @Builder.Default
    private String accentColor = "#10B981";

    @Column(name = "background_color", length = 7)
    private String backgroundColor;

    @Column(name = "text_color", length = 7)
    private String textColor;

    @Column(name = "border_radius")
    @Builder.Default
    private Integer borderRadius = 8;

    @Column(name = "font_family", length = 100)
    private String fontFamily;

    @Column(name = "position", length = 20)
    @Builder.Default
    private String position = "bottom-right";

    // ===== Content Settings =====
    @Column(name = "show_rating")
    @Builder.Default
    private Boolean showRating = true;

    @Column(name = "show_review_count")
    @Builder.Default
    private Boolean showReviewCount = true;

    @Column(name = "show_badge")
    @Builder.Default
    private Boolean showBadge = true;

    @Column(name = "show_business_name")
    @Builder.Default
    private Boolean showBusinessName = true;

    @Column(name = "show_reputul_branding")
    @Builder.Default
    private Boolean showReputulBranding = true;

    @Column(name = "min_rating")
    @Builder.Default
    private Integer minRating = 4;

    @Column(name = "max_reviews")
    @Builder.Default
    private Integer maxReviews = 12;

    @Column(name = "show_photos")
    @Builder.Default
    private Boolean showPhotos = true;

    @Column(name = "show_reviewer_name")
    @Builder.Default
    private Boolean showReviewerName = true;

    @Column(name = "show_review_date")
    @Builder.Default
    private Boolean showReviewDate = true;

    @Column(name = "show_platform_source")
    @Builder.Default
    private Boolean showPlatformSource = true;

    // ===== Popup Widget Settings =====
    @Column(name = "popup_delay_seconds")
    @Builder.Default
    private Integer popupDelaySeconds = 3;

    @Column(name = "popup_display_duration")
    @Builder.Default
    private Integer popupDisplayDuration = 8;

    @Column(name = "popup_interval_seconds")
    @Builder.Default
    private Integer popupIntervalSeconds = 15;

    @Column(name = "popup_max_per_session")
    @Builder.Default
    private Integer popupMaxPerSession = 5;

    @Column(name = "popup_enabled_mobile")
    @Builder.Default
    private Boolean popupEnabledMobile = true;

    @Column(name = "popup_animation", length = 20)
    @Builder.Default
    private String popupAnimation = "slide";

    // ===== Carousel/Grid Widget Settings =====
    @Column(name = "layout", length = 20)
    @Builder.Default
    private String layout = "grid";

    @Column(name = "columns")
    @Builder.Default
    private Integer columns = 3;

    @Column(name = "auto_scroll")
    @Builder.Default
    private Boolean autoScroll = false;

    @Column(name = "scroll_speed")
    @Builder.Default
    private Integer scrollSpeed = 5;

    @Column(name = "show_navigation_arrows")
    @Builder.Default
    private Boolean showNavigationArrows = true;

    @Column(name = "show_pagination_dots")
    @Builder.Default
    private Boolean showPaginationDots = true;

    @Column(name = "card_shadow")
    @Builder.Default
    private Boolean cardShadow = true;

    // ===== Badge Widget Settings =====
    @Column(name = "badge_style", length = 20)
    @Builder.Default
    private String badgeStyle = "standard";

    @Column(name = "badge_size", length = 20)
    @Builder.Default
    private String badgeSize = "medium";

    // ===== Analytics =====
    @Column(name = "total_impressions")
    @Builder.Default
    private Long totalImpressions = 0L;

    @Column(name = "total_clicks")
    @Builder.Default
    private Long totalClicks = 0L;

    @Column(name = "last_impression_at")
    private OffsetDateTime lastImpressionAt;

    @Column(name = "last_click_at")
    private OffsetDateTime lastClickAt;

    // ===== Security & Status =====
    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "allowed_domains", columnDefinition = "TEXT")
    private String allowedDomains;

    // ===== Timestamps =====
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ===== Enums =====
    public enum WidgetType {
        POPUP,      // Floating notification popups
        BADGE,      // Compact trust badge
        CAROUSEL,   // Scrolling review carousel
        GRID        // Review grid/wall
    }

    // ===== Lifecycle Callbacks =====
    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ===== Utility Methods =====

    /**
     * Increment impression count
     */
    public void recordImpression() {
        this.totalImpressions = (this.totalImpressions == null ? 0 : this.totalImpressions) + 1;
        this.lastImpressionAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Increment click count
     */
    public void recordClick() {
        this.totalClicks = (this.totalClicks == null ? 0 : this.totalClicks) + 1;
        this.lastClickAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Check if a domain is allowed to load this widget
     */
    public boolean isDomainAllowed(String domain) {
        if (allowedDomains == null || allowedDomains.isBlank()) {
            return true; // All domains allowed if not specified
        }

        String normalizedDomain = domain.toLowerCase().trim();
        String[] allowed = allowedDomains.toLowerCase().split(",");

        for (String allowedDomain : allowed) {
            String trimmed = allowedDomain.trim();
            if (trimmed.equals(normalizedDomain) ||
                    normalizedDomain.endsWith("." + trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate click-through rate
     */
    public double getClickThroughRate() {
        if (totalImpressions == null || totalImpressions == 0) {
            return 0.0;
        }
        return (double) (totalClicks != null ? totalClicks : 0) / totalImpressions * 100;
    }
}