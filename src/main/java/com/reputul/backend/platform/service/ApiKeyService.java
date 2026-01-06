package com.reputul.backend.platform.service;

import com.reputul.backend.platform.dto.integration.ApiKeyResponse;
import com.reputul.backend.platform.dto.integration.CreateApiKeyRequest;
import com.reputul.backend.platform.dto.integration.CreateApiKeyResponse;
import com.reputul.backend.platform.entity.ApiKey;
import com.reputul.backend.platform.repository.ApiKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int KEY_LENGTH_BYTES = 32; // 256 bits
    private static final String KEY_PREFIX = "rpt_live_";

    public ApiKeyService(ApiKeyRepository apiKeyRepository, PasswordEncoder passwordEncoder) {
        this.apiKeyRepository = apiKeyRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create a new API key for an organization
     * Returns the full key only once - it's never stored in plain text
     */
    @Transactional
    public CreateApiKeyResponse createApiKey(Long organizationId, Long userId, CreateApiKeyRequest request) {
        log.info("Creating API key '{}' for organization {}", request.getName(), organizationId);

        // Check for duplicate names
        if (apiKeyRepository.existsByOrganizationIdAndName(organizationId, request.getName())) {
            throw new IllegalArgumentException("API key with name '" + request.getName() + "' already exists");
        }

        // Generate secure random key
        String fullKey = generateApiKey();
        String keyHash = passwordEncoder.encode(fullKey);
        String keyPrefix = fullKey.substring(0, Math.min(20, fullKey.length())); // First 20 chars for display

        // Create entity
        ApiKey apiKey = ApiKey.builder()
                .organizationId(organizationId)                .name(request.getName())
                .keyHash(keyHash)
                .keyPrefix(keyPrefix)
                .createdBy(userId)
                .build();

        apiKey = apiKeyRepository.save(apiKey);

        log.info("API key created successfully: id={}, name={}", apiKey.getId(), apiKey.getName());

        return CreateApiKeyResponse.builder()
                .id(apiKey.getId())
                .name(apiKey.getName())
                .key(fullKey) // ONLY RETURNED ONCE!
                .keyPrefix(keyPrefix)
                .createdAt(apiKey.getCreatedAt())
                .build();
    }

    /**
     * List all API keys for an organization (without full keys)
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listApiKeys(Long organizationId) {
        List<ApiKey> apiKeys = apiKeyRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId);

        return apiKeys.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * List only active API keys for an organization
     */
    @Transactional(readOnly = true)
    public List<ApiKeyResponse> listActiveApiKeys(Long organizationId) {
        List<ApiKey> apiKeys = apiKeyRepository.findActiveByOrganizationId(organizationId);

        return apiKeys.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Revoke (soft delete) an API key
     */
    @Transactional
    public void revokeApiKey(Long organizationId, UUID keyId, Long userId) {
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("API key not found"));

        // Security check: ensure key belongs to organization
        if (!apiKey.getOrganizationId().equals(UUID.fromString(organizationId.toString()))) {
            throw new SecurityException("API key does not belong to this organization");
        }

        if (apiKey.getRevokedAt() != null) {
            throw new IllegalStateException("API key is already revoked");
        }

        apiKey.setRevokedAt(LocalDateTime.now());
        apiKey.setRevokedBy(userId);
        apiKeyRepository.save(apiKey);

        log.info("API key revoked: id={}, name={}, organization={}", keyId, apiKey.getName(), organizationId);
    }

    /**
     * Authenticate an API key and return organization ID if valid
     * Also updates last_used_at timestamp
     */
    @Transactional
    public Optional<Long> authenticateApiKey(String providedKey) {
        // Hash the provided key
        // Note: We need to find by prefix first, then verify hash for performance
        // For now, we'll iterate through potential matches

        List<ApiKey> potentialKeys = apiKeyRepository.findAll().stream()
                .filter(ApiKey::isActive)
                .filter(key -> providedKey.startsWith(key.getKeyPrefix().substring(0, Math.min(10, key.getKeyPrefix().length()))))
                .collect(Collectors.toList());

        for (ApiKey apiKey : potentialKeys) {
            if (passwordEncoder.matches(providedKey, apiKey.getKeyHash())) {
                // Valid key found - update last used timestamp
                apiKey.setLastUsedAt(LocalDateTime.now());
                apiKeyRepository.save(apiKey);

                log.debug("API key authenticated successfully: keyId={}, org={}", apiKey.getId(), apiKey.getOrganizationId());
                return Optional.of(Long.parseLong(apiKey.getOrganizationId().toString()));
            }
        }

        log.warn("API key authentication failed: invalid or revoked key");
        return Optional.empty();
    }

    /**
     * Generate a secure random API key
     * Format: rpt_live_<base64-encoded-random-bytes>
     */
    private String generateApiKey() {
        byte[] randomBytes = new byte[KEY_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return KEY_PREFIX + encodedKey;
    }

    /**
     * Convert entity to response DTO
     */
    private ApiKeyResponse toResponse(ApiKey apiKey) {
        return ApiKeyResponse.builder()
                .id(apiKey.getId())
                .name(apiKey.getName())
                .keyPrefix(apiKey.getKeyPrefix())
                .lastUsedAt(apiKey.getLastUsedAt())
                .createdAt(apiKey.getCreatedAt())
                .revokedAt(apiKey.getRevokedAt())
                .isActive(apiKey.isActive())
                .build();
    }
}