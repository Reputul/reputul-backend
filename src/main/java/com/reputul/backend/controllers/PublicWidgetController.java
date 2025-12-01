package com.reputul.backend.controllers;

import com.reputul.backend.dto.WidgetDtos.*;
import com.reputul.backend.services.WidgetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Public Widget Controller (No Authentication Required)
 *
 * Provides public API endpoints for widget embed scripts to fetch data.
 * These endpoints are called from customer websites and must:
 * - Not require authentication
 * - Support CORS from any domain (or allowed domains)
 * - Be optimized for performance with caching
 * - Track analytics (impressions, clicks)
 *
 * Endpoints:
 * - GET  /api/public/widgets/{widgetKey}/data       - Get widget data for rendering
 * - POST /api/public/widgets/{widgetKey}/impression - Track impression
 * - POST /api/public/widgets/{widgetKey}/click      - Track click
 */
@RestController
@RequestMapping("/api/public/widgets")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*", maxAge = 3600) // Allow all origins for widget embeds
public class PublicWidgetController {

    private final WidgetService widgetService;

    // ================================================================
    // WIDGET DATA ENDPOINT (Main API for embed scripts)
    // ================================================================

    /**
     * Get widget data for rendering
     * GET /api/public/widgets/{widgetKey}/data
     *
     * Called by embed scripts to fetch:
     * - Business info (name, logo)
     * - Reputation data (rating, review count, badge)
     * - Reviews (filtered and formatted)
     * - Style configuration
     *
     * Query params:
     * - callback: JSONP callback name (optional, for older browsers)
     */
    @GetMapping("/{widgetKey}/data")
    public ResponseEntity<?> getWidgetData(
            @PathVariable String widgetKey,
            @RequestParam(required = false) String callback,
            HttpServletRequest request) {

        log.debug("Widget data request for key: {}", widgetKey);

        try {
            // Extract requesting domain from Origin or Referer header
            String requestDomain = extractDomain(request);

            // Get widget data (includes domain validation)
            WidgetDataDto data = widgetService.getPublicWidgetData(widgetKey, requestDomain);

            // Track impression asynchronously
            try {
                widgetService.trackImpression(widgetKey);
            } catch (Exception e) {
                log.warn("Failed to track impression for widget {}: {}", widgetKey, e.getMessage());
            }

            // Support JSONP for cross-origin requests from older browsers
            if (callback != null && !callback.isBlank() && isValidCallbackName(callback)) {
                String jsonp = callback + "(" + toJson(data) + ");";
                return ResponseEntity.ok()
                        .header("Content-Type", "application/javascript; charset=utf-8")
                        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                        .body(jsonp);
            }

            // Standard JSON response with cache headers
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES))
                    .body(data);

        } catch (RuntimeException e) {
            log.warn("Widget data error for key {}: {}", widgetKey, e.getMessage());

            // Return empty data response instead of error to avoid breaking customer websites
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.MINUTES))
                    .body(Map.of(
                            "error", true,
                            "message", "Widget not available",
                            "widgetKey", widgetKey
                    ));
        } catch (Exception e) {
            log.error("Unexpected error fetching widget data for {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.ok()
                    .body(Map.of(
                            "error", true,
                            "message", "Service temporarily unavailable"
                    ));
        }
    }

    /**
     * Get widget data with minimal fields (for badge widgets)
     * GET /api/public/widgets/{widgetKey}/badge
     */
    @GetMapping("/{widgetKey}/badge")
    public ResponseEntity<?> getBadgeData(
            @PathVariable String widgetKey,
            HttpServletRequest request) {

        try {
            String requestDomain = extractDomain(request);
            WidgetDataDto data = widgetService.getPublicWidgetData(widgetKey, requestDomain);

            // Return minimal data for badge rendering
            Map<String, Object> badgeData = Map.of(
                    "rating", data.getRating() != null ? data.getRating() : 0.0,
                    "formattedRating", data.getFormattedRating() != null ? data.getFormattedRating() : "0.0",
                    "totalReviews", data.getTotalReviews() != null ? data.getTotalReviews() : 0,
                    "badge", data.getBadge() != null ? data.getBadge() : "Unranked",
                    "badgeColor", data.getBadgeColor() != null ? data.getBadgeColor() : "#6B7280",
                    "businessName", data.getBusinessName() != null ? data.getBusinessName() : ""
            );

            widgetService.trackImpression(widgetKey);

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES))
                    .body(badgeData);

        } catch (Exception e) {
            log.warn("Badge data error for key {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.ok()
                    .body(Map.of("error", true, "message", "Widget not available"));
        }
    }

    // ================================================================
    // ANALYTICS TRACKING ENDPOINTS
    // ================================================================

    /**
     * Track widget impression
     * POST /api/public/widgets/{widgetKey}/impression
     *
     * Called when widget is displayed on a page
     */
    @PostMapping("/{widgetKey}/impression")
    public ResponseEntity<?> trackImpression(
            @PathVariable String widgetKey,
            @RequestBody(required = false) Map<String, Object> metadata,
            HttpServletRequest request) {

        try {
            widgetService.trackImpression(widgetKey);

            log.debug("Tracked impression for widget {} from {}",
                    widgetKey, extractDomain(request));

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("success", true));

        } catch (Exception e) {
            log.warn("Failed to track impression for {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.ok().body(Map.of("success", false));
        }
    }

    /**
     * Track widget click
     * POST /api/public/widgets/{widgetKey}/click
     *
     * Called when user clicks on widget (e.g., to view more reviews)
     */
    @PostMapping("/{widgetKey}/click")
    public ResponseEntity<?> trackClick(
            @PathVariable String widgetKey,
            @RequestBody(required = false) Map<String, Object> metadata,
            HttpServletRequest request) {

        try {
            widgetService.trackClick(widgetKey);

            log.debug("Tracked click for widget {} from {}",
                    widgetKey, extractDomain(request));

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("success", true));

        } catch (Exception e) {
            log.warn("Failed to track click for {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.ok().body(Map.of("success", false));
        }
    }

    /**
     * Batch track multiple events
     * POST /api/public/widgets/track
     *
     * For efficient tracking of multiple events in single request
     */
    @PostMapping("/track")
    public ResponseEntity<?> trackBatch(@RequestBody Map<String, Object> events) {
        try {
            // Handle impressions
            if (events.containsKey("impressions")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> impressions = (java.util.List<String>) events.get("impressions");
                for (String widgetKey : impressions) {
                    try {
                        widgetService.trackImpression(widgetKey);
                    } catch (Exception e) {
                        log.debug("Failed to track impression for {}", widgetKey);
                    }
                }
            }

            // Handle clicks
            if (events.containsKey("clicks")) {
                @SuppressWarnings("unchecked")
                java.util.List<String> clicks = (java.util.List<String>) events.get("clicks");
                for (String widgetKey : clicks) {
                    try {
                        widgetService.trackClick(widgetKey);
                    } catch (Exception e) {
                        log.debug("Failed to track click for {}", widgetKey);
                    }
                }
            }

            return ResponseEntity.ok().body(Map.of("success", true));

        } catch (Exception e) {
            log.warn("Batch tracking error: {}", e.getMessage());
            return ResponseEntity.ok().body(Map.of("success", false));
        }
    }

    // ================================================================
    // HELPER METHODS
    // ================================================================

    /**
     * Extract domain from request headers
     */
    private String extractDomain(HttpServletRequest request) {
        // Try Origin header first (for CORS requests)
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return extractDomainFromUrl(origin);
        }

        // Fall back to Referer
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            return extractDomainFromUrl(referer);
        }

        return null;
    }

    /**
     * Extract domain name from URL
     */
    private String extractDomainFromUrl(String url) {
        try {
            java.net.URL parsed = new java.net.URL(url);
            return parsed.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validate JSONP callback name to prevent XSS
     */
    private boolean isValidCallbackName(String callback) {
        // Only allow alphanumeric characters, underscores, and dots
        return callback.matches("^[a-zA-Z_][a-zA-Z0-9_.]*$");
    }

    /**
     * Convert object to JSON string (simple implementation)
     */
    private String toJson(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules(); // For Java 8 date/time support
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON serialization error", e);
            return "{}";
        }
    }
}