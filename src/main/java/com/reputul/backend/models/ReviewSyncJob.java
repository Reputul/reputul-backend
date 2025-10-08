package com.reputul.backend.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Entity
@Table(name = "review_sync_jobs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewSyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credential_id", nullable = false)
    private ChannelCredential credential;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "platform_type", nullable = false)
    private String platformType;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false)
    private SyncStatus status = SyncStatus.PENDING;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    // Results
    @Builder.Default
    @Column(name = "reviews_fetched")
    private Integer reviewsFetched = 0;

    @Builder.Default
    @Column(name = "reviews_new")
    private Integer reviewsNew = 0;

    @Builder.Default
    @Column(name = "reviews_updated")
    private Integer reviewsUpdated = 0;

    @Builder.Default
    @Column(name = "reviews_skipped")
    private Integer reviewsSkipped = 0;

    // Error tracking
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "error_details", columnDefinition = "JSONB")
    private String errorDetailsJson;

    @Transient
    private Map<String, Object> errorDetails;

    @Builder.Default
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        syncErrorDetailsToJson();
    }

    @PreUpdate
    protected void onUpdate() {
        syncErrorDetailsToJson();
    }

    @PostLoad
    protected void onLoad() {
        syncErrorDetailsFromJson();
    }

    private void syncErrorDetailsToJson() {
        if (errorDetails == null || errorDetails.isEmpty()) {
            this.errorDetailsJson = null;
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.errorDetailsJson = mapper.writeValueAsString(errorDetails);
        } catch (JsonProcessingException e) {
            this.errorDetailsJson = null;
        }
    }

    private void syncErrorDetailsFromJson() {
        if (errorDetailsJson == null || errorDetailsJson.trim().isEmpty()) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            this.errorDetails = mapper.readValue(errorDetailsJson, Map.class);
        } catch (JsonProcessingException e) {
            // Ignore
        }
    }

    public enum SyncStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }
}