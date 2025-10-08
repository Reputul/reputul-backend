package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Entity
@Table(name = "reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Builder.Default
    private String source = "manual";

    // Add these fields to existing Review entity
    @Column(name = "source_review_id")
    private String sourceReviewId;

    @Column(name = "source_review_url", columnDefinition = "TEXT")
    private String sourceReviewUrl;

    @Column(name = "reviewer_photo_url", columnDefinition = "TEXT")
    private String reviewerPhotoUrl;

    @Builder.Default
    @Column(name = "platform_verified")
    private Boolean platformVerified = false;

    @Column(name = "platform_response", columnDefinition = "TEXT")
    private String platformResponse;

    @Column(name = "platform_response_at")
    private OffsetDateTime platformResponseAt;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "source_metadata", columnDefinition = "JSONB")
    private String sourceMetadataJson;

    @Transient
    private Map<String, Object> sourceMetadata;

    // NEW: Customer information (for feedback tracking)
    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    //Optional link back to Customer entity (for tracking)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private OffsetDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        if (source == null) {
            source = "manual";
        }
    }
}