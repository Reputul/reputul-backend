package com.reputul.backend.dto;

import com.reputul.backend.models.WidgetConfiguration;
import com.reputul.backend.models.WidgetConfiguration.WidgetType;
import lombok.*;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Widget Data Transfer Objects
 *
 * Contains all DTOs for widget-related API operations:
 * - WidgetConfigDto: Full configuration for create/update
 * - WidgetResponseDto: Response with all settings
 * - WidgetDataDto: Public widget data for embed scripts
 * - WidgetReviewDto: Sanitized review for public display
 * - WidgetAnalyticsDto: Analytics data
 * - EmbedCodeDto: Generated embed code
 */
public class WidgetDtos {

    // ================================================================
    // WIDGET CONFIGURATION DTO (for create/update requests)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetConfigDto {

        @NotNull(message = "Business ID is required")
        private Long businessId;

        @NotNull(message = "Widget type is required")
        private WidgetType widgetType;

        @Size(max = 100, message = "Name must be less than 100 characters")
        private String name;

        // Display Settings
        private String theme;

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format")
        private String primaryColor;

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format")
        private String accentColor;

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format")
        private String backgroundColor;

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Invalid hex color format")
        private String textColor;

        @Min(0) @Max(50)
        private Integer borderRadius;

        private String fontFamily;
        private String position;

        // Content Settings
        private Boolean showRating;
        private Boolean showReviewCount;
        private Boolean showBadge;
        private Boolean showBusinessName;
        private Boolean showReputulBranding;

        @Min(1) @Max(5)
        private Integer minRating;

        @Min(1) @Max(50)
        private Integer maxReviews;

        private Boolean showPhotos;
        private Boolean showReviewerName;
        private Boolean showReviewDate;
        private Boolean showPlatformSource;

        // Popup Settings
        @Min(0) @Max(60)
        private Integer popupDelaySeconds;

        @Min(3) @Max(30)
        private Integer popupDisplayDuration;

        @Min(5) @Max(120)
        private Integer popupIntervalSeconds;

        @Min(1) @Max(20)
        private Integer popupMaxPerSession;

        private Boolean popupEnabledMobile;
        private String popupAnimation;

        // Carousel/Grid Settings
        private String layout;

        @Min(1) @Max(6)
        private Integer columns;

        private Boolean autoScroll;

        @Min(3) @Max(15)
        private Integer scrollSpeed;

        private Boolean showNavigationArrows;
        private Boolean showPaginationDots;
        private Boolean cardShadow;

        // Badge Settings
        private String badgeStyle;
        private String badgeSize;

        // Security
        private Boolean isActive;
        private String allowedDomains;
    }

    // ================================================================
    // WIDGET RESPONSE DTO (full response with all fields)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetResponseDto {
        private Long id;
        private Long businessId;
        private String businessName;
        private Long organizationId;
        private String widgetKey;
        private WidgetType widgetType;
        private String name;

        // Display Settings
        private String theme;
        private String primaryColor;
        private String accentColor;
        private String backgroundColor;
        private String textColor;
        private Integer borderRadius;
        private String fontFamily;
        private String position;

        // Content Settings
        private Boolean showRating;
        private Boolean showReviewCount;
        private Boolean showBadge;
        private Boolean showBusinessName;
        private Boolean showReputulBranding;
        private Integer minRating;
        private Integer maxReviews;
        private Boolean showPhotos;
        private Boolean showReviewerName;
        private Boolean showReviewDate;
        private Boolean showPlatformSource;

        // Popup Settings
        private Integer popupDelaySeconds;
        private Integer popupDisplayDuration;
        private Integer popupIntervalSeconds;
        private Integer popupMaxPerSession;
        private Boolean popupEnabledMobile;
        private String popupAnimation;

        // Carousel/Grid Settings
        private String layout;
        private Integer columns;
        private Boolean autoScroll;
        private Integer scrollSpeed;
        private Boolean showNavigationArrows;
        private Boolean showPaginationDots;
        private Boolean cardShadow;

        // Badge Settings
        private String badgeStyle;
        private String badgeSize;

        // Analytics
        private Long totalImpressions;
        private Long totalClicks;
        private Double clickThroughRate;
        private OffsetDateTime lastImpressionAt;
        private OffsetDateTime lastClickAt;

        // Status
        private Boolean isActive;
        private String allowedDomains;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;

        // Embed Code Preview
        private String embedCodePreview;

        /**
         * Convert entity to response DTO
         */
        public static WidgetResponseDto fromEntity(WidgetConfiguration entity) {
            if (entity == null) return null;

            return WidgetResponseDto.builder()
                    .id(entity.getId())
                    .businessId(entity.getBusiness() != null ? entity.getBusiness().getId() : null)
                    .businessName(entity.getBusiness() != null ? entity.getBusiness().getName() : null)
                    .organizationId(entity.getOrganization() != null ? entity.getOrganization().getId() : null)
                    .widgetKey(entity.getWidgetKey())
                    .widgetType(entity.getWidgetType())
                    .name(entity.getName())
                    .theme(entity.getTheme())
                    .primaryColor(entity.getPrimaryColor())
                    .accentColor(entity.getAccentColor())
                    .backgroundColor(entity.getBackgroundColor())
                    .textColor(entity.getTextColor())
                    .borderRadius(entity.getBorderRadius())
                    .fontFamily(entity.getFontFamily())
                    .position(entity.getPosition())
                    .showRating(entity.getShowRating())
                    .showReviewCount(entity.getShowReviewCount())
                    .showBadge(entity.getShowBadge())
                    .showBusinessName(entity.getShowBusinessName())
                    .showReputulBranding(entity.getShowReputulBranding())
                    .minRating(entity.getMinRating())
                    .maxReviews(entity.getMaxReviews())
                    .showPhotos(entity.getShowPhotos())
                    .showReviewerName(entity.getShowReviewerName())
                    .showReviewDate(entity.getShowReviewDate())
                    .showPlatformSource(entity.getShowPlatformSource())
                    .popupDelaySeconds(entity.getPopupDelaySeconds())
                    .popupDisplayDuration(entity.getPopupDisplayDuration())
                    .popupIntervalSeconds(entity.getPopupIntervalSeconds())
                    .popupMaxPerSession(entity.getPopupMaxPerSession())
                    .popupEnabledMobile(entity.getPopupEnabledMobile())
                    .popupAnimation(entity.getPopupAnimation())
                    .layout(entity.getLayout())
                    .columns(entity.getColumns())
                    .autoScroll(entity.getAutoScroll())
                    .scrollSpeed(entity.getScrollSpeed())
                    .showNavigationArrows(entity.getShowNavigationArrows())
                    .showPaginationDots(entity.getShowPaginationDots())
                    .cardShadow(entity.getCardShadow())
                    .badgeStyle(entity.getBadgeStyle())
                    .badgeSize(entity.getBadgeSize())
                    .totalImpressions(entity.getTotalImpressions())
                    .totalClicks(entity.getTotalClicks())
                    .clickThroughRate(entity.getClickThroughRate())
                    .lastImpressionAt(entity.getLastImpressionAt())
                    .lastClickAt(entity.getLastClickAt())
                    .isActive(entity.getIsActive())
                    .allowedDomains(entity.getAllowedDomains())
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
        }
    }

    // ================================================================
    // PUBLIC WIDGET DATA DTO (for embed scripts - no auth)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetDataDto {
        private String widgetKey;
        private String widgetType;

        // Business Info
        private String businessName;
        private String businessLogoUrl;
        private String businessIndustry;

        // Reputation Data
        private Double rating;
        private String formattedRating;
        private Integer totalReviews;
        private String badge;
        private String badgeColor;

        // Reviews (sanitized for public display)
        private List<WidgetReviewDto> reviews;

        // Widget Configuration (for styling)
        private WidgetStyleDto style;

        // Timestamps
        private OffsetDateTime dataUpdatedAt;
    }

    // ================================================================
    // WIDGET REVIEW DTO (sanitized review for public display)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetReviewDto {
        private Long id;
        private Integer rating;
        private String comment;
        private String reviewerName;        // May be anonymized
        private String reviewerInitials;    // "JD" for "John Doe"
        private String platform;            // "google", "facebook", "reputul"
        private String platformIcon;        // URL to platform icon
        private OffsetDateTime createdAt;
        private String relativeTime;        // "2 days ago"
    }

    // ================================================================
    // WIDGET STYLE DTO (styling configuration for embed)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetStyleDto {
        private String theme;
        private String primaryColor;
        private String accentColor;
        private String backgroundColor;
        private String textColor;
        private Integer borderRadius;
        private String fontFamily;
        private String position;

        // Type-specific settings
        private Integer popupDelaySeconds;
        private Integer popupDisplayDuration;
        private Integer popupIntervalSeconds;
        private Integer popupMaxPerSession;
        private Boolean popupEnabledMobile;
        private String popupAnimation;

        private String layout;
        private Integer columns;
        private Boolean autoScroll;
        private Integer scrollSpeed;
        private Boolean showNavigationArrows;
        private Boolean showPaginationDots;
        private Boolean cardShadow;

        private String badgeStyle;
        private String badgeSize;

        // Content visibility
        private Boolean showRating;
        private Boolean showReviewCount;
        private Boolean showBadge;
        private Boolean showBusinessName;
        private Boolean showReputulBranding;
        private Boolean showPhotos;
        private Boolean showReviewerName;
        private Boolean showReviewDate;
        private Boolean showPlatformSource;
    }

    // ================================================================
    // WIDGET ANALYTICS DTO
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetAnalyticsDto {
        private Long widgetId;
        private String widgetKey;
        private String widgetName;
        private String widgetType;
        private String businessName;

        private Long totalImpressions;
        private Long totalClicks;
        private Double clickThroughRate;

        private OffsetDateTime lastImpressionAt;
        private OffsetDateTime lastClickAt;

        private Boolean isActive;
        private OffsetDateTime createdAt;

        // Time-series data (optional, for detailed analytics)
        private List<DailyStats> dailyStats;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class DailyStats {
            private String date;
            private Long impressions;
            private Long clicks;
            private Double ctr;
        }
    }

    // ================================================================
    // EMBED CODE DTO
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EmbedCodeDto {
        private String widgetKey;
        private String widgetType;
        private String htmlCode;
        private String scriptOnlyCode;
        private String wordPressShortcode;
        private String reactComponent;
        private String cdnUrl;
    }

    // ================================================================
    // WIDGET LIST SUMMARY DTO (for dashboard display)
    // ================================================================
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WidgetSummaryDto {
        private Long id;
        private String widgetKey;
        private String name;
        private String widgetType;
        private String businessName;
        private Boolean isActive;
        private Long totalImpressions;
        private Long totalClicks;
        private Double clickThroughRate;
        private OffsetDateTime createdAt;

        public static WidgetSummaryDto fromEntity(WidgetConfiguration entity) {
            return WidgetSummaryDto.builder()
                    .id(entity.getId())
                    .widgetKey(entity.getWidgetKey())
                    .name(entity.getName())
                    .widgetType(entity.getWidgetType().name())
                    .businessName(entity.getBusiness() != null ? entity.getBusiness().getName() : null)
                    .isActive(entity.getIsActive())
                    .totalImpressions(entity.getTotalImpressions())
                    .totalClicks(entity.getTotalClicks())
                    .clickThroughRate(entity.getClickThroughRate())
                    .createdAt(entity.getCreatedAt())
                    .build();
        }
    }
}