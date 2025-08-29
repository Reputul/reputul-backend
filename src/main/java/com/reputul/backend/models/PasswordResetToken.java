package com.reputul.backend.models;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private Boolean used = false;

    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

    // Constructors
    public PasswordResetToken() {}

    public PasswordResetToken(User user, String token, OffsetDateTime expiresAt) {
        this.user = user;
        this.token = token;
        this.expiresAt = expiresAt;
        this.used = false;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Boolean getUsed() {
        return used;
    }

    public void setUsed(Boolean used) {
        this.used = used;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}