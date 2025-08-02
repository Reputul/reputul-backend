package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    // NEW: Customer information (for feedback tracking)
    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    // NEW: Optional link back to Customer entity (for tracking)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "business_id")
    private Business business;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (source == null) {
            source = "manual";
        }
    }
}