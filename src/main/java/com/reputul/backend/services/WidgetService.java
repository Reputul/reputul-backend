package com.reputul.backend.services;

import com.reputul.backend.dto.WidgetDtos.*;
import com.reputul.backend.models.*;
import com.reputul.backend.models.WidgetConfiguration.WidgetType;
import com.reputul.backend.repositories.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Widget Service
 *
 * Handles all business logic for social proof widgets including:
 * - Widget CRUD operations
 * - Public widget data retrieval
 * - Analytics tracking
 * - Embed code generation
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WidgetService {

    private final WidgetConfigurationRepository widgetRepository;
    private final BusinessRepository businessRepository;
    private final ReviewRepository reviewRepository;
    private final ReputationService reputationService;
    private final BadgeService badgeService;

    @Value("${app.widget.cdn-base-url:https://cdn.reputul.com/widgets/v1}")
    private String cdnBaseUrl;

    @Value("${app.widget.api-base-url:https://api.reputul.com}")
    private String apiBaseUrl;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String WIDGET_KEY_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    // ================================================================
    // WIDGET CRUD OPERATIONS
    // ================================================================

    /**
     * Create a new widget configuration
     */
    @Transactional
    public WidgetResponseDto createWidget(WidgetConfigDto dto, User user) {
        log.info("Creating widget for business {} by user {}", dto.getBusinessId(), user.getEmail());

        // Validate business ownership
        Business business = businessRepository.findByIdAndOrganizationId(
                        dto.getBusinessId(), user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

        // Generate unique widget key
        String widgetKey = generateUniqueWidgetKey();

        // Build widget configuration
        WidgetConfiguration widget = WidgetConfiguration.builder()
                .business(business)
                .organization(user.getOrganization())
                .widgetKey(widgetKey)
                .widgetType(dto.getWidgetType())
                .name(dto.getName() != null ? dto.getName() : generateDefaultName(dto.getWidgetType(), business.getName()))
                .build();

        // Apply configuration from DTO
        applyConfigFromDto(widget, dto);

        // Save and return
        WidgetConfiguration saved = widgetRepository.save(widget);
        log.info("Created widget {} with key {}", saved.getId(), widgetKey);

        WidgetResponseDto response = WidgetResponseDto.fromEntity(saved);
        response.setEmbedCodePreview(generateEmbedCodePreview(saved));
        return response;
    }

    /**
     * Update an existing widget configuration
     */
    @Transactional
    public WidgetResponseDto updateWidget(Long widgetId, WidgetConfigDto dto, User user) {
        log.info("Updating widget {} by user {}", widgetId, user.getEmail());

        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        // Apply updates
        applyConfigFromDto(widget, dto);

        // Update name if provided
        if (dto.getName() != null) {
            widget.setName(dto.getName());
        }

        WidgetConfiguration saved = widgetRepository.save(widget);
        log.info("Updated widget {}", widgetId);

        WidgetResponseDto response = WidgetResponseDto.fromEntity(saved);
        response.setEmbedCodePreview(generateEmbedCodePreview(saved));
        return response;
    }

    /**
     * Get widget by ID
     */
    @Transactional(readOnly = true)
    public WidgetResponseDto getWidget(Long widgetId, User user) {
        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        WidgetResponseDto response = WidgetResponseDto.fromEntity(widget);
        response.setEmbedCodePreview(generateEmbedCodePreview(widget));
        return response;
    }

    /**
     * Get all widgets for user's organization
     */
    @Transactional(readOnly = true)
    public List<WidgetSummaryDto> getAllWidgets(User user) {
        List<WidgetConfiguration> widgets = widgetRepository
                .findByOrganizationIdOrderByCreatedAtDesc(user.getOrganization().getId());

        return widgets.stream()
                .map(WidgetSummaryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Get widgets for a specific business
     */
    @Transactional(readOnly = true)
    public List<WidgetSummaryDto> getWidgetsByBusiness(Long businessId, User user) {
        // Validate business access
        businessRepository.findByIdAndOrganizationId(businessId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Business not found or access denied"));

        List<WidgetConfiguration> widgets = widgetRepository
                .findByOrganizationIdAndBusinessIdOrderByCreatedAtDesc(
                        user.getOrganization().getId(), businessId);

        return widgets.stream()
                .map(WidgetSummaryDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Delete a widget
     */
    @Transactional
    public void deleteWidget(Long widgetId, User user) {
        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        widgetRepository.delete(widget);
        log.info("Deleted widget {} by user {}", widgetId, user.getEmail());
    }

    /**
     * Toggle widget active status
     */
    @Transactional
    public WidgetResponseDto toggleWidgetStatus(Long widgetId, User user) {
        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        widget.setIsActive(!widget.getIsActive());
        WidgetConfiguration saved = widgetRepository.save(widget);

        log.info("Toggled widget {} status to {}", widgetId, saved.getIsActive());
        return WidgetResponseDto.fromEntity(saved);
    }

    // ================================================================
    // PUBLIC WIDGET DATA (No Auth Required)
    // ================================================================

    /**
     * Get widget data for public embed
     * This is called by the embed scripts to render widgets
     */
    @Transactional(readOnly = true)
    public WidgetDataDto getPublicWidgetData(String widgetKey, String requestDomain) {
        log.debug("Fetching public widget data for key: {}", widgetKey);

        WidgetConfiguration widget = widgetRepository.findByWidgetKeyAndIsActiveTrue(widgetKey)
                .orElseThrow(() -> new RuntimeException("Widget not found or inactive"));

        // Validate domain if restrictions are set
        if (requestDomain != null && !widget.isDomainAllowed(requestDomain)) {
            log.warn("Domain {} not allowed for widget {}", requestDomain, widgetKey);
            throw new RuntimeException("Domain not allowed");
        }

        Business business = widget.getBusiness();
        if (business == null) {
            throw new RuntimeException("Associated business not found");
        }

        // Get reputation data
        double rating = reputationService.calculateReputulRating(business.getId());
        long totalReviews = reviewRepository.countByBusinessId(business.getId());
        String badge = badgeService.determineEnhancedBadge(rating, (int) totalReviews);
        String badgeColor = badgeService.getBadgeColor(badge);

        // Get reviews filtered by min rating and max count
        List<Review> reviews = reviewRepository.findByBusinessIdAndRatingGreaterThanEqual(
                business.getId(), widget.getMinRating());

        // Sort by date desc and limit
        reviews = reviews.stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(widget.getMaxReviews())
                .collect(Collectors.toList());

        // Convert to widget review DTOs
        List<WidgetReviewDto> widgetReviews = reviews.stream()
                .map(review -> convertToWidgetReview(review, widget))
                .collect(Collectors.toList());

        // Build style configuration
        WidgetStyleDto style = buildStyleDto(widget);

        return WidgetDataDto.builder()
                .widgetKey(widgetKey)
                .widgetType(widget.getWidgetType().name())
                .businessName(widget.getShowBusinessName() ? business.getName() : null)
                .businessLogoUrl(business.getLogoUrl())
                .businessIndustry(business.getIndustry())
                .rating(rating)
                .formattedRating(String.format("%.1f", rating))
                .totalReviews((int) totalReviews)
                .badge(widget.getShowBadge() ? badge : null)
                .badgeColor(badgeColor)
                .reviews(widgetReviews)
                .style(style)
                .dataUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
    }

    /**
     * Track widget impression (async update)
     */
    @Transactional
    public void trackImpression(String widgetKey) {
        widgetRepository.incrementImpressions(widgetKey);
        log.debug("Recorded impression for widget: {}", widgetKey);
    }

    /**
     * Track widget click (async update)
     */
    @Transactional
    public void trackClick(String widgetKey) {
        widgetRepository.incrementClicks(widgetKey);
        log.debug("Recorded click for widget: {}", widgetKey);
    }

    // ================================================================
    // EMBED CODE GENERATION
    // ================================================================

    /**
     * Generate embed codes for a widget
     */
    @Transactional(readOnly = true)
    public EmbedCodeDto getEmbedCode(Long widgetId, User user) {
        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        return generateEmbedCode(widget);
    }

    /**
     * Get embed code by widget key (for quick access)
     */
    @Transactional(readOnly = true)
    public EmbedCodeDto getEmbedCodeByKey(String widgetKey, User user) {
        WidgetConfiguration widget = widgetRepository.findByWidgetKeyAndOrganizationId(
                        widgetKey, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        return generateEmbedCode(widget);
    }

    // ================================================================
    // ANALYTICS
    // ================================================================

    /**
     * Get analytics for a specific widget
     */
    @Transactional(readOnly = true)
    public WidgetAnalyticsDto getWidgetAnalytics(Long widgetId, User user) {
        WidgetConfiguration widget = widgetRepository.findByIdAndOrganizationId(
                        widgetId, user.getOrganization().getId())
                .orElseThrow(() -> new RuntimeException("Widget not found or access denied"));

        return WidgetAnalyticsDto.builder()
                .widgetId(widget.getId())
                .widgetKey(widget.getWidgetKey())
                .widgetName(widget.getName())
                .widgetType(widget.getWidgetType().name())
                .businessName(widget.getBusiness() != null ? widget.getBusiness().getName() : null)
                .totalImpressions(widget.getTotalImpressions())
                .totalClicks(widget.getTotalClicks())
                .clickThroughRate(widget.getClickThroughRate())
                .lastImpressionAt(widget.getLastImpressionAt())
                .lastClickAt(widget.getLastClickAt())
                .isActive(widget.getIsActive())
                .createdAt(widget.getCreatedAt())
                .build();
    }

    /**
     * Get aggregate analytics for organization
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getOrganizationWidgetAnalytics(User user) {
        Long orgId = user.getOrganization().getId();

        long totalWidgets = widgetRepository.countByOrganizationId(orgId);
        Long totalImpressions = widgetRepository.getTotalImpressionsByOrganization(orgId);
        Long totalClicks = widgetRepository.getTotalClicksByOrganization(orgId);

        double overallCtr = totalImpressions > 0
                ? (double) totalClicks / totalImpressions * 100
                : 0.0;

        List<WidgetConfiguration> topWidgets = widgetRepository.findTopPerformingWidgets(orgId);
        List<WidgetSummaryDto> topPerforming = topWidgets.stream()
                .limit(5)
                .map(WidgetSummaryDto::fromEntity)
                .collect(Collectors.toList());

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("totalWidgets", totalWidgets);
        analytics.put("totalImpressions", totalImpressions);
        analytics.put("totalClicks", totalClicks);
        analytics.put("overallClickThroughRate", overallCtr);
        analytics.put("topPerformingWidgets", topPerforming);

        return analytics;
    }

    // ================================================================
    // PRIVATE HELPER METHODS
    // ================================================================

    /**
     * Generate unique widget key
     */
    private String generateUniqueWidgetKey() {
        String key;
        do {
            key = generateRandomKey(12);
        } while (widgetRepository.existsByWidgetKey(key));
        return key;
    }

    private String generateRandomKey(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(WIDGET_KEY_CHARS.charAt(SECURE_RANDOM.nextInt(WIDGET_KEY_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Generate default widget name
     */
    private String generateDefaultName(WidgetType type, String businessName) {
        String typeName = switch (type) {
            case POPUP -> "Social Proof Popup";
            case BADGE -> "Trust Badge";
            case CAROUSEL -> "Review Carousel";
            case GRID -> "Review Wall";
        };
        return businessName + " - " + typeName;
    }

    /**
     * Apply configuration from DTO to entity
     */
    private void applyConfigFromDto(WidgetConfiguration widget, WidgetConfigDto dto) {
        // Display settings
        if (dto.getTheme() != null) widget.setTheme(dto.getTheme());
        if (dto.getPrimaryColor() != null) widget.setPrimaryColor(dto.getPrimaryColor());
        if (dto.getAccentColor() != null) widget.setAccentColor(dto.getAccentColor());
        if (dto.getBackgroundColor() != null) widget.setBackgroundColor(dto.getBackgroundColor());
        if (dto.getTextColor() != null) widget.setTextColor(dto.getTextColor());
        if (dto.getBorderRadius() != null) widget.setBorderRadius(dto.getBorderRadius());
        if (dto.getFontFamily() != null) widget.setFontFamily(dto.getFontFamily());
        if (dto.getPosition() != null) widget.setPosition(dto.getPosition());

        // Content settings
        if (dto.getShowRating() != null) widget.setShowRating(dto.getShowRating());
        if (dto.getShowReviewCount() != null) widget.setShowReviewCount(dto.getShowReviewCount());
        if (dto.getShowBadge() != null) widget.setShowBadge(dto.getShowBadge());
        if (dto.getShowBusinessName() != null) widget.setShowBusinessName(dto.getShowBusinessName());
        if (dto.getShowReputulBranding() != null) widget.setShowReputulBranding(dto.getShowReputulBranding());
        if (dto.getMinRating() != null) widget.setMinRating(dto.getMinRating());
        if (dto.getMaxReviews() != null) widget.setMaxReviews(dto.getMaxReviews());
        if (dto.getShowPhotos() != null) widget.setShowPhotos(dto.getShowPhotos());
        if (dto.getShowReviewerName() != null) widget.setShowReviewerName(dto.getShowReviewerName());
        if (dto.getShowReviewDate() != null) widget.setShowReviewDate(dto.getShowReviewDate());
        if (dto.getShowPlatformSource() != null) widget.setShowPlatformSource(dto.getShowPlatformSource());

        // Popup settings
        if (dto.getPopupDelaySeconds() != null) widget.setPopupDelaySeconds(dto.getPopupDelaySeconds());
        if (dto.getPopupDisplayDuration() != null) widget.setPopupDisplayDuration(dto.getPopupDisplayDuration());
        if (dto.getPopupIntervalSeconds() != null) widget.setPopupIntervalSeconds(dto.getPopupIntervalSeconds());
        if (dto.getPopupMaxPerSession() != null) widget.setPopupMaxPerSession(dto.getPopupMaxPerSession());
        if (dto.getPopupEnabledMobile() != null) widget.setPopupEnabledMobile(dto.getPopupEnabledMobile());
        if (dto.getPopupAnimation() != null) widget.setPopupAnimation(dto.getPopupAnimation());

        // Carousel/Grid settings
        if (dto.getLayout() != null) widget.setLayout(dto.getLayout());
        if (dto.getColumns() != null) widget.setColumns(dto.getColumns());
        if (dto.getAutoScroll() != null) widget.setAutoScroll(dto.getAutoScroll());
        if (dto.getScrollSpeed() != null) widget.setScrollSpeed(dto.getScrollSpeed());
        if (dto.getShowNavigationArrows() != null) widget.setShowNavigationArrows(dto.getShowNavigationArrows());
        if (dto.getShowPaginationDots() != null) widget.setShowPaginationDots(dto.getShowPaginationDots());
        if (dto.getCardShadow() != null) widget.setCardShadow(dto.getCardShadow());

        // Badge settings
        if (dto.getBadgeStyle() != null) widget.setBadgeStyle(dto.getBadgeStyle());
        if (dto.getBadgeSize() != null) widget.setBadgeSize(dto.getBadgeSize());

        // Status
        if (dto.getIsActive() != null) widget.setIsActive(dto.getIsActive());
        if (dto.getAllowedDomains() != null) widget.setAllowedDomains(dto.getAllowedDomains());
    }

    /**
     * Convert Review entity to WidgetReviewDto
     */
    private WidgetReviewDto convertToWidgetReview(Review review, WidgetConfiguration widget) {
        String reviewerName = widget.getShowReviewerName() && review.getCustomerName() != null
                ? review.getCustomerName()
                : "Customer";

        String initials = getInitials(reviewerName);

        String platform = review.getSource() != null ? review.getSource().toLowerCase() : "reputul";
        String platformIcon = getPlatformIcon(platform);

        String relativeTime = getRelativeTime(review.getCreatedAt());

        return WidgetReviewDto.builder()
                .id(review.getId())
                .rating(review.getRating())
                .comment(review.getComment())
                .reviewerName(reviewerName)
                .reviewerInitials(initials)
                .platform(platform)
                .platformIcon(platformIcon)
                .createdAt(widget.getShowReviewDate() ? review.getCreatedAt() : null)
                .relativeTime(widget.getShowReviewDate() ? relativeTime : null)
                .build();
    }

    private String getInitials(String name) {
        if (name == null || name.isBlank()) return "C";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private String getPlatformIcon(String platform) {
        return switch (platform.toLowerCase()) {
            case "google", "google_my_business" -> "https://cdn.reputul.com/icons/google.svg";
            case "facebook" -> "https://cdn.reputul.com/icons/facebook.svg";
            case "yelp" -> "https://cdn.reputul.com/icons/yelp.svg";
            default -> "https://cdn.reputul.com/icons/reputul.svg";
        };
    }

    private String getRelativeTime(OffsetDateTime dateTime) {
        if (dateTime == null) return "";

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long days = ChronoUnit.DAYS.between(dateTime, now);

        if (days == 0) return "Today";
        if (days == 1) return "Yesterday";
        if (days < 7) return days + " days ago";
        if (days < 30) return (days / 7) + " week" + (days / 7 > 1 ? "s" : "") + " ago";
        if (days < 365) return (days / 30) + " month" + (days / 30 > 1 ? "s" : "") + " ago";
        return (days / 365) + " year" + (days / 365 > 1 ? "s" : "") + " ago";
    }

    /**
     * Build WidgetStyleDto from configuration
     */
    private WidgetStyleDto buildStyleDto(WidgetConfiguration widget) {
        return WidgetStyleDto.builder()
                .theme(widget.getTheme())
                .primaryColor(widget.getPrimaryColor())
                .accentColor(widget.getAccentColor())
                .backgroundColor(widget.getBackgroundColor())
                .textColor(widget.getTextColor())
                .borderRadius(widget.getBorderRadius())
                .fontFamily(widget.getFontFamily())
                .position(widget.getPosition())
                .popupDelaySeconds(widget.getPopupDelaySeconds())
                .popupDisplayDuration(widget.getPopupDisplayDuration())
                .popupIntervalSeconds(widget.getPopupIntervalSeconds())
                .popupMaxPerSession(widget.getPopupMaxPerSession())
                .popupEnabledMobile(widget.getPopupEnabledMobile())
                .popupAnimation(widget.getPopupAnimation())
                .layout(widget.getLayout())
                .columns(widget.getColumns())
                .autoScroll(widget.getAutoScroll())
                .scrollSpeed(widget.getScrollSpeed())
                .showNavigationArrows(widget.getShowNavigationArrows())
                .showPaginationDots(widget.getShowPaginationDots())
                .cardShadow(widget.getCardShadow())
                .badgeStyle(widget.getBadgeStyle())
                .badgeSize(widget.getBadgeSize())
                .showRating(widget.getShowRating())
                .showReviewCount(widget.getShowReviewCount())
                .showBadge(widget.getShowBadge())
                .showBusinessName(widget.getShowBusinessName())
                .showReputulBranding(widget.getShowReputulBranding())
                .showPhotos(widget.getShowPhotos())
                .showReviewerName(widget.getShowReviewerName())
                .showReviewDate(widget.getShowReviewDate())
                .showPlatformSource(widget.getShowPlatformSource())
                .build();
    }

    /**
     * Generate embed code for a widget
     */
    private EmbedCodeDto generateEmbedCode(WidgetConfiguration widget) {
        String key = widget.getWidgetKey();
        String type = widget.getWidgetType().name().toLowerCase();

        // HTML embed code
        String htmlCode = switch (widget.getWidgetType()) {
            case POPUP -> String.format("""
                    <!-- Reputul Social Proof Popup -->
                    <script>
                      window.reputulConfig = { key: '%s' };
                    </script>
                    <script src="%s/popup.js" async></script>
                    """, key, cdnBaseUrl);
            case BADGE -> String.format("""
                    <!-- Reputul Trust Badge -->
                    <div id="reputul-badge" data-widget-key="%s"></div>
                    <script src="%s/badge.js" async></script>
                    """, key, cdnBaseUrl);
            case CAROUSEL -> String.format("""
                    <!-- Reputul Review Carousel -->
                    <div id="reputul-carousel" data-widget-key="%s"></div>
                    <script src="%s/carousel.js" async></script>
                    """, key, cdnBaseUrl);
            case GRID -> String.format("""
                    <!-- Reputul Review Wall -->
                    <div id="reputul-reviews" data-widget-key="%s"></div>
                    <script src="%s/grid.js" async></script>
                    """, key, cdnBaseUrl);
        };

        // Script-only code
        String scriptOnly = String.format("""
                <script src="%s/%s.js?key=%s" async></script>
                """, cdnBaseUrl, type, key);

        // WordPress shortcode
        String wordPressShortcode = String.format("[reputul_%s key=\"%s\"]", type, key);

        // React component
        String reactComponent = String.format("""
                import { ReputulWidget } from '@reputul/react-widgets';
                
                <ReputulWidget type="%s" widgetKey="%s" />
                """, type, key);

        return EmbedCodeDto.builder()
                .widgetKey(key)
                .widgetType(type)
                .htmlCode(htmlCode)
                .scriptOnlyCode(scriptOnly)
                .wordPressShortcode(wordPressShortcode)
                .reactComponent(reactComponent)
                .cdnUrl(cdnBaseUrl)
                .build();
    }

    /**
     * Generate preview of embed code
     */
    private String generateEmbedCodePreview(WidgetConfiguration widget) {
        String key = widget.getWidgetKey();
        return switch (widget.getWidgetType()) {
            case POPUP -> String.format("<script src=\"%s/popup.js?key=%s\"></script>", cdnBaseUrl, key);
            case BADGE -> String.format("<div data-reputul-badge=\"%s\"></div>", key);
            case CAROUSEL -> String.format("<div data-reputul-carousel=\"%s\"></div>", key);
            case GRID -> String.format("<div data-reputul-reviews=\"%s\"></div>", key);
        };
    }
}