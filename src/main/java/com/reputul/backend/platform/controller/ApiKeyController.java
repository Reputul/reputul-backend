package com.reputul.backend.platform.controller;

import com.reputul.backend.models.User;
import com.reputul.backend.platform.dto.integration.ApiKeyResponse;
import com.reputul.backend.platform.dto.integration.CreateApiKeyRequest;
import com.reputul.backend.platform.dto.integration.CreateApiKeyResponse;
import com.reputul.backend.platform.service.ApiKeyService;
import com.reputul.backend.repositories.UserRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller for managing API keys
 * Requires authentication - accessed through Settings > Integrations UI
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@Slf4j
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;

    public ApiKeyController(ApiKeyService apiKeyService, UserRepository userRepository) {
        this.apiKeyService = apiKeyService;
        this.userRepository = userRepository;
    }

    /**
     * List all API keys for the user's organization
     * GET /api/v1/api-keys
     */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys(@AuthenticationPrincipal UserDetails userDetails) {
        User user = getUserFromDetails(userDetails);
        if (user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<ApiKeyResponse> apiKeys = apiKeyService.listApiKeys(user.getOrganization().getId());
        return ResponseEntity.ok(apiKeys);
    }

    /**
     * List only active API keys for the user's organization
     * GET /api/v1/api-keys/active
     */
    @GetMapping("/active")
    public ResponseEntity<List<ApiKeyResponse>> listActiveApiKeys(@AuthenticationPrincipal UserDetails userDetails) {
        User user = getUserFromDetails(userDetails);
        if (user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<ApiKeyResponse> apiKeys = apiKeyService.listActiveApiKeys(user.getOrganization().getId());
        return ResponseEntity.ok(apiKeys);
    }

    /**
     * Create a new API key
     * POST /api/v1/api-keys
     *
     * IMPORTANT: The full API key is only returned once in this response.
     * It cannot be retrieved again.
     */
    @PostMapping
    public ResponseEntity<CreateApiKeyResponse> createApiKey(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateApiKeyRequest request) {

        User user = getUserFromDetails(userDetails);
        if (user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Only OWNER and ADMIN can create API keys
        if (!user.isAdmin()) {
            log.warn("User {} attempted to create API key without permission", user.getEmail());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            CreateApiKeyResponse response = apiKeyService.createApiKey(
                    user.getOrganization().getId(),
                    user.getId(),
                    request
            );

            log.info("API key created by user {}: name={}", user.getEmail(), request.getName());
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            log.warn("Failed to create API key: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Revoke an API key
     * DELETE /api/v1/api-keys/{keyId}
     */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Map<String, String>> revokeApiKey(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID keyId) {

        User user = getUserFromDetails(userDetails);
        if (user.getOrganization() == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Only OWNER and ADMIN can revoke API keys
        if (!user.isAdmin()) {
            log.warn("User {} attempted to revoke API key without permission", user.getEmail());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            apiKeyService.revokeApiKey(user.getOrganization().getId(), keyId, user.getId());
            log.info("API key revoked by user {}: keyId={}", user.getEmail(), keyId);
            return ResponseEntity.ok(Map.of("message", "API key revoked successfully"));

        } catch (IllegalArgumentException e) {
            log.warn("Failed to revoke API key: {}", e.getMessage());
            return ResponseEntity.notFound().build();

        } catch (SecurityException e) {
            log.warn("Security violation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();

        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Helper method to get User entity from UserDetails
     */
    private User getUserFromDetails(UserDetails userDetails) {
        return userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}