package com.reputul.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "businesses")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Business {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String industry;
    private String phone;
    private String website;
    private String address;

    private Double reputationScore;

    private String badge;

    private LocalDateTime createdAt;

    // NEW: Review Platform Integration Fields
    private String googlePlaceId;
    private String facebookPageUrl;
    private String yelpPageUrl;

    @Builder.Default
    private Boolean reviewPlatformsConfigured = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User owner;

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore
    private java.util.List<Review> reviews;

    @OneToOne(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore
    private Subscription subscription;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}