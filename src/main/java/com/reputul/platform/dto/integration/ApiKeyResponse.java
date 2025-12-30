package com.reputul.platform.dto.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for listing API keys (without full key)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyResponse {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("key_prefix")
    private String keyPrefix;

    @JsonProperty("last_used_at")
    private LocalDateTime lastUsedAt;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("revoked_at")
    private LocalDateTime revokedAt;

    @JsonProperty("is_active")
    private boolean isActive;
}