package com.reputul.backend.platform.controller;

import com.reputul.backend.platform.dto.integration.ZapierContactRequest;
import com.reputul.backend.platform.dto.integration.ZapierReviewRequestRequest;
import com.reputul.backend.platform.dto.integration.ZapierWebhookResponse;
import com.reputul.backend.platform.service.ApiKeyService;
import com.reputul.backend.platform.service.ZapierWebhookService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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

    // Rate limiting: 1000 requests per hour per API key
    private final Map<String, Bucket> rateLimitBuckets = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_CAPACITY = 1000;
    private static final Duration RATE_LIMIT_PERIOD = Duration.ofHours(1);

    public ZapierWebhookController(ZapierWebhookService zapierWebhookService, ApiKeyService apiKeyService) {
        this.zapierWebhookService = zapierWebhookService;
        this.apiKeyService = apiKeyService;
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
     * Health check endpoint for Zapier to verify connectivity
     * GET /api/v1/integrations/zapier/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health(
            @RequestHeader(value = "X-API-Key", required = true) String apiKey) {

        // Authenticate API key
        Optional<Long> organizationId = apiKeyService.authenticateApiKey(apiKey);
        if (organizationId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "error", "message", "Invalid API key"));
        }

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "organization_id", organizationId.get().toString(),
                "message", "Reputul API is healthy"
        ));
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