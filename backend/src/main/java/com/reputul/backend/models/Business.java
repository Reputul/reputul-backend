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

    private LocalDateTime createdAt;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User owner;

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore // avoid infinite recursion
    private java.util.List<Review> reviews;

    @OneToOne(mappedBy = "business", cascade = CascadeType.ALL)
    @JsonIgnore
    private Subscription subscription;

}
