package com.reputul.backend.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Entity
@Table(name = "channel_credentials")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_type", nullable = false)
    private PlatformType platformType;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private CredentialStatus status = CredentialStatus.PENDING;

    // OAuth tokens (should be encrypted at rest in production)
    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    // Platform-specific metadata stored as JSONB
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadataJson;

    @Transient
    private Map<String, Object> metadata;

    // Sync tracking
    @Column(name = "last_sync_at")
    private OffsetDateTime lastSyncAt;

    @Column(name = "last_sync_status")
    private String lastSyncStatus;

    @Column(name = "sync_error_message", columnDefinition = "TEXT")
    private String syncErrorMessage;

    @Column(name = "next_sync_scheduled")
    private OffsetDateTime nextSyncScheduled;

    // Audit fields
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        syncMetadataToJson();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PostLoad
    protected void onLoad() {
        syncMetadataFromJson();
    }

    private void syncMetadataToJson() {
        if (metadata == null || metadata.isEmpty()) {
            this.metadataJson = null;
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.metadataJson = mapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            this.metadataJson = null;
        }
    }

    private void syncMetadataFromJson() {
        if (metadataJson == null || metadataJson.trim().isEmpty()) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.metadata = mapper.readValue(metadataJson, Map.class);
        } catch (JsonProcessingException e) {
            // Ignore parsing errors
        }
    }

    public boolean isTokenExpired() {
        return tokenExpiresAt != null && OffsetDateTime.now(ZoneOffset.UTC).isAfter(tokenExpiresAt);
    }

    public boolean needsRefresh() {
        // Refresh if token expires within 5 minutes
        return tokenExpiresAt != null &&
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5).isAfter(tokenExpiresAt);
    }

    public enum PlatformType {
        GOOGLE_MY_BUSINESS,
        FACEBOOK,
        YELP,
        TRUSTPILOT,
        BETTER_BUSINESS_BUREAU,
        ANGI,
        HOUZZ,
        HOMEADVISOR,
        THUMBTACK
    }

    public enum CredentialStatus {
        PENDING,    // OAuth flow initiated but not completed
        ACTIVE,     // Connected and working
        EXPIRED,    // Token expired, needs refresh
        ERROR,      // Connection error
        REVOKED     // User revoked access
    }
}