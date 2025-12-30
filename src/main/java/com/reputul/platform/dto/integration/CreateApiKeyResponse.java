package com.reputul.platform.dto.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO when creating a new API key
 * WARNING: The full key is only returned once during creation
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateApiKeyResponse {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("key")
    private String key; // Full key - only returned once!

    @JsonProperty("key_prefix")
    private String keyPrefix;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("warning")
    @Builder.Default
    private String warning = "Save this key securely. It will not be shown again.";
}