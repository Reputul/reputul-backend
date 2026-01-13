package com.reputul.backend.platform.controller;

import com.reputul.backend.models.Review;
import com.reputul.backend.platform.dto.integration.ZapierContactRequest;
import com.reputul.backend.platform.dto.integration.ZapierReviewRequestRequest;
import com.reputul.backend.platform.dto.integration.ZapierWebhookResponse;
import com.reputul.backend.platform.service.ApiKeyService;
import com.reputul.backend.platform.service.ZapierWebhookService;
import com.reputul.backend.repositories.ReviewRepository;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook controller for Zapier integrations
 * Handles contact creation and review request webhooks
 */
@RestController
@RequestMapping("/api/v1/integrations/zapier")
@Slf4j
public class ZapierWebhookController {

    private final ZapierWebhookService zapierWebhookService;
    private final ApiKeyService apiKeyService;
    private final ReviewRepository reviewRepository;

    // Rate limiting: 1000 requests per hour per API key
    private final Map<String, Bucket> rateLimitBuckets = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_CAPACITY = 1000;
    private static final Duration RATE_LIMIT_PERIOD = Duration.ofHours(1);

    public ZapierWebhookController(
            ZapierWebhookService zapierWebhookService,
            ApiKeyService apiKeyService,
            ReviewRepository reviewRepository) {
        this.zapierWebhookService = zapierWebhookService;
        this.apiKeyService = apiKeyService;
        this.reviewRepository = reviewRepository;
    }

    /**
     * Webhook endpoint to create or update a contact
     * POST /api/v1/integrations/zapier/contacts
     */
    @PostMapping("/contacts")
    public ResponseEntity<ZapierWebhookResponse> createContact(
            @RequestHeader(value = "X-API-Key", required = true) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ZapierContactRequest request) {

        log.info("Received Zapier contact creation request: name={}", request.getCustomerName());

        // Authenticate API key
        Optional<Long> organizationId = apiKeyService.authenticateApiKey(apiKey);
        if (organizationId.isEmpty()) {
            log.warn("Invalid or revoked API key provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ZapierWebhookResponse.error(
                            "INVALID_API_KEY",
                            "Invalid or revoked API key"
                    ));
        }

        // Check rate limit
        if (!checkRateLimit(apiKey)) {
            log.warn("Rate limit exceeded for API key");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ZapierWebhookResponse.error(
                            "RATE_LIMIT_EXCEEDED",
                            "Rate limit exceeded. Maximum " + RATE_LIMIT_CAPACITY + " requests per hour."
                    ));
        }

        // Check idempotency
        Optional<ZapierWebhookResponse> cachedResponse = zapierWebhookService.checkIdempotency(
                idempotencyKey,
                "/api/v1/integrations/zapier/contacts"
        );
        if (cachedResponse.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok(cachedResponse.get());
        }

        // Process request
        ZapierWebhookResponse response = zapierWebhookService.createContact(organizationId.get(), request);

        // Store idempotency key
        zapierWebhookService.storeIdempotencyKey(
                idempotencyKey,
                "/api/v1/integrations/zapier/contacts",
                organizationId.get(),
                response,
                response.isSuccess() ? 200 : 400
        );

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Webhook endpoint to create contact and send review request
     * POST /api/v1/integrations/zapier/review-requests
     */
    @PostMapping("/review-requests")
    public ResponseEntity<ZapierWebhookResponse> createReviewRequest(
            @RequestHeader(value = "X-API-Key", required = true) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ZapierReviewRequestRequest request) {

        log.info("Received Zapier review request: name={}", request.getCustomerName());

        // Authenticate API key
        Optional<Long> organizationId = apiKeyService.authenticateApiKey(apiKey);
        if (organizationId.isEmpty()) {
            log.warn("Invalid or revoked API key provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ZapierWebhookResponse.error(
                            "INVALID_API_KEY",
                            "Invalid or revoked API key"
                    ));
        }

        // Check rate limit
        if (!checkRateLimit(apiKey)) {
            log.warn("Rate limit exceeded for API key");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ZapierWebhookResponse.error(
                            "RATE_LIMIT_EXCEEDED",
                            "Rate limit exceeded. Maximum " + RATE_LIMIT_CAPACITY + " requests per hour."
                    ));
        }

        // Check idempotency
        Optional<ZapierWebhookResponse> cachedResponse = zapierWebhookService.checkIdempotency(
                idempotencyKey,
                "/api/v1/integrations/zapier/review-requests"
        );
        if (cachedResponse.isPresent()) {
            log.info("Returning cached response for idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok(cachedResponse.get());
        }

        // Process request
        ZapierWebhookResponse response = zapierWebhookService.createReviewRequest(organizationId.get(), request);

        // Store idempotency key
        zapierWebhookService.storeIdempotencyKey(
                idempotencyKey,
                "/api/v1/integrations/zapier/review-requests",
                organizationId.get(),
                response,
                response.isSuccess() ? 200 : 400
        );

        HttpStatus status = response.isSuccess() ? HttpStatus.OK : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Public health check endpoint for Zapier to verify connectivity
     * No authentication required - this is a standard practice for health checks
     * GET /api/v1/integrations/zapier/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        // If API key provided, validate it and return organization-specific info
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            Optional<Long> organizationId = apiKeyService.authenticateApiKey(apiKey);
            if (organizationId.isPresent()) {
                log.info("Authenticated health check for organization {}", organizationId.get());
                return ResponseEntity.ok(Map.of(
                        "status", "ok",
                        "service", "Reputul Zapier API",
                        "authenticated", true,
                        "organization_id", organizationId.get(),
                        "timestamp", OffsetDateTime.now().toString()
                ));
            } else {
                log.warn("Invalid API key provided for health check");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of(
                                "status", "error",
                                "message", "Invalid API key",
                                "timestamp", OffsetDateTime.now().toString()
                        ));
            }
        }

        // Public health check - no API key required
        log.debug("Public health check request");
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "Reputul Zapier API",
                "authenticated", false,
                "timestamp", OffsetDateTime.now().toString(),
                "message", "API is healthy and accepting requests"
        ));
    }

    /**
     * NEW: Get recent reviews for Zapier trigger (polling)
     * GET /api/v1/integrations/zapier/reviews/recent
     */
    @GetMapping("/reviews/recent")
    public ResponseEntity<?> getRecentReviews(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestParam(defaultValue = "10") int limit) {

        // Authenticate
        Optional<Long> orgId = apiKeyService.authenticateApiKey(apiKey);
        if (orgId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid API key"));
        }

        log.info("Fetching recent reviews for organization {}", orgId.get());

        // Get recent reviews for this organization
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Review> reviewPage = reviewRepository.findByBusinessOrganizationId(orgId.get(), pageable);

        // Map to Zapier-friendly format
        List<Map<String, Object>> zapierReviews = reviewPage.getContent().stream()
                .map(review -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", review.getId().toString());
                    map.put("business_id", review.getBusiness().getId().toString());
                    map.put("business_name", review.getBusiness().getName());
                    map.put("customer_name", review.getCustomerName() != null ? review.getCustomerName() : "");
                    map.put("customer_email", review.getCustomerEmail() != null ? review.getCustomerEmail() : "");
                    map.put("rating", review.getRating());
                    map.put("comment", review.getComment() != null ? review.getComment() : "");
                    map.put("source", review.getSource());
                    map.put("created_at", review.getCreatedAt().toString());
                    map.put("review_url", review.getSourceReviewUrl() != null ? review.getSourceReviewUrl() : "");
                    return map;
                })
                .toList();

        log.info("Returning {} recent reviews for organization {}", zapierReviews.size(), orgId.get());
        return ResponseEntity.ok(zapierReviews);
    }

    /**
     * Check rate limit using token bucket algorithm
     * Returns true if request is allowed, false if rate limit exceeded
     */
    private boolean checkRateLimit(String apiKey) {
        Bucket bucket = rateLimitBuckets.computeIfAbsent(apiKey, k -> {
            Bandwidth limit = Bandwidth.classic(RATE_LIMIT_CAPACITY, Refill.intervally(RATE_LIMIT_CAPACITY, RATE_LIMIT_PERIOD));
            return Bucket.builder()
                    .addLimit(limit)
                    .build();
        });

        return bucket.tryConsume(1);
    }
}