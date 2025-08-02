package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "subscriptions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String plan;       // e.g., "free", "pro", "agency"
    private String status;     // e.g., "active", "trialing", "canceled"
    private boolean trial;

    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private LocalDateTime renewalDate;

    @OneToOne
    @JoinColumn(name = "business_id")
    private Business business;
}
