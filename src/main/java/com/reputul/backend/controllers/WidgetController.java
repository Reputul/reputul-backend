package com.reputul.backend.controllers;

import com.reputul.backend.dto.WidgetDtos.*;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.WidgetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Widget Controller (Authenticated)
 *
 * Provides REST API endpoints for managing social proof widgets.
 * All endpoints require authentication and enforce organization-level access control.
 *
 * Endpoints:
 * - GET    /api/v1/widgets                          - List all widgets
 * - GET    /api/v1/widgets/{id}                     - Get widget by ID
 * - GET    /api/v1/widgets/business/{businessId}   - Get widgets for business
 * - POST   /api/v1/widgets                          - Create new widget
 * - PUT    /api/v1/widgets/{id}                     - Update widget
 * - DELETE /api/v1/widgets/{id}                     - Delete widget
 * - POST   /api/v1/widgets/{id}/toggle              - Toggle widget status
 * - GET    /api/v1/widgets/{id}/embed-code          - Get embed code
 * - GET    /api/v1/widgets/{id}/analytics           - Get widget analytics
 * - GET    /api/v1/widgets/analytics/overview       - Get organization analytics
 */
@RestController
@RequestMapping("/api/v1/widgets")
@RequiredArgsConstructor
@Slf4j
public class WidgetController {

    private final WidgetService widgetService;
    private final UserRepository userRepository;

    // ================================================================
    // WIDGET CRUD ENDPOINTS
    // ================================================================

    /**
     * List all widgets for the authenticated user's organization
     * GET /api/v1/widgets
     */
    @GetMapping
    public ResponseEntity<List<WidgetSummaryDto>> getAllWidgets(
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            List<WidgetSummaryDto> widgets = widgetService.getAllWidgets(user);
            return ResponseEntity.ok(widgets);
        } catch (Exception e) {
            log.error("Error fetching widgets: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get widget by ID
     * GET /api/v1/widgets/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<WidgetResponseDto> getWidget(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            WidgetResponseDto widget = widgetService.getWidget(id, user);
            return ResponseEntity.ok(widget);
        } catch (RuntimeException e) {
            log.warn("Widget not found or access denied: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get widgets for a specific business
     * GET /api/v1/widgets/business/{businessId}
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<WidgetSummaryDto>> getWidgetsByBusiness(
            @PathVariable Long businessId,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            List<WidgetSummaryDto> widgets = widgetService.getWidgetsByBusiness(businessId, user);
            return ResponseEntity.ok(widgets);
        } catch (RuntimeException e) {
            log.warn("Business not found or access denied: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error fetching widgets for business {}: {}", businessId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Create a new widget
     * POST /api/v1/widgets
     */
    @PostMapping
    public ResponseEntity<?> createWidget(
            @Valid @RequestBody WidgetConfigDto configDto,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            WidgetResponseDto widget = widgetService.createWidget(configDto, user);
            log.info("Created widget {} for user {}", widget.getId(), user.getEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body(widget);
        } catch (RuntimeException e) {
            log.warn("Failed to create widget: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error creating widget: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to create widget"));
        }
    }

    /**
     * Update an existing widget
     * PUT /api/v1/widgets/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateWidget(
            @PathVariable Long id,
            @Valid @RequestBody WidgetConfigDto configDto,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            WidgetResponseDto widget = widgetService.updateWidget(id, configDto, user);
            log.info("Updated widget {} by user {}", id, user.getEmail());
            return ResponseEntity.ok(widget);
        } catch (RuntimeException e) {
            log.warn("Failed to update widget {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error updating widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to update widget"));
        }
    }

    /**
     * Delete a widget
     * DELETE /api/v1/widgets/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWidget(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            widgetService.deleteWidget(id, user);
            log.info("Deleted widget {} by user {}", id, user.getEmail());
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            log.warn("Failed to delete widget {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error deleting widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to delete widget"));
        }
    }

    /**
     * Toggle widget active status
     * POST /api/v1/widgets/{id}/toggle
     */
    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggleWidgetStatus(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            WidgetResponseDto widget = widgetService.toggleWidgetStatus(id, user);
            log.info("Toggled widget {} status to {} by user {}",
                    id, widget.getIsActive(), user.getEmail());
            return ResponseEntity.ok(widget);
        } catch (RuntimeException e) {
            log.warn("Failed to toggle widget {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error toggling widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to toggle widget status"));
        }
    }

    // ================================================================
    // EMBED CODE ENDPOINTS
    // ================================================================

    /**
     * Get embed code for a widget
     * GET /api/v1/widgets/{id}/embed-code
     */
    @GetMapping("/{id}/embed-code")
    public ResponseEntity<?> getEmbedCode(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            EmbedCodeDto embedCode = widgetService.getEmbedCode(id, user);
            return ResponseEntity.ok(embedCode);
        } catch (RuntimeException e) {
            log.warn("Failed to get embed code for widget {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting embed code for widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get embed code"));
        }
    }

    /**
     * Get embed code by widget key (convenience endpoint)
     * GET /api/v1/widgets/key/{widgetKey}/embed-code
     */
    @GetMapping("/key/{widgetKey}/embed-code")
    public ResponseEntity<?> getEmbedCodeByKey(
            @PathVariable String widgetKey,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            EmbedCodeDto embedCode = widgetService.getEmbedCodeByKey(widgetKey, user);
            return ResponseEntity.ok(embedCode);
        } catch (RuntimeException e) {
            log.warn("Failed to get embed code for widget key {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting embed code for widget key {}: {}", widgetKey, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get embed code"));
        }
    }

    // ================================================================
    // ANALYTICS ENDPOINTS
    // ================================================================

    /**
     * Get analytics for a specific widget
     * GET /api/v1/widgets/{id}/analytics
     */
    @GetMapping("/{id}/analytics")
    public ResponseEntity<?> getWidgetAnalytics(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            WidgetAnalyticsDto analytics = widgetService.getWidgetAnalytics(id, user);
            return ResponseEntity.ok(analytics);
        } catch (RuntimeException e) {
            log.warn("Failed to get analytics for widget {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Error getting analytics for widget {}: {}", id, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get widget analytics"));
        }
    }

    /**
     * Get aggregate analytics for organization
     * GET /api/v1/widgets/analytics/overview
     */
    @GetMapping("/analytics/overview")
    public ResponseEntity<?> getOrganizationAnalytics(
            @AuthenticationPrincipal UserDetails userDetails) {
        try {
            User user = getCurrentUser(userDetails);
            Map<String, Object> analytics = widgetService.getOrganizationWidgetAnalytics(user);
            return ResponseEntity.ok(analytics);
        } catch (Exception e) {
            log.error("Error getting organization analytics: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get organization analytics"));
        }
    }

    // ================================================================
    // HELPER METHODS
    // ================================================================

    private User getCurrentUser(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}