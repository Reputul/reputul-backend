package com.reputul.backend.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"organization"})
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Name is required")
    private String name;

    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    @Column(nullable = false, unique = true)
    private String email;

    // ===== NEW: Phone field for SMS Alerts =====
    @Column(name = "phone")
    private String phone;

    @NotBlank(message = "Password is required")
    @Column(nullable = false)
    private String password;

    // ===== ADDED: Organization relationship =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    // ===== ADDED: Role within organization =====
    @Column(name = "role")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private UserRole role = UserRole.STAFF;

    // ===== ADDED: Invitation tracking =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by")
    private User invitedBy;

    @Column(name = "invited_at")
    private OffsetDateTime invitedAt;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    // ===== ADDED: User role enum =====
    public enum UserRole {
        OWNER,  // Can manage billing, users, and all settings
        ADMIN,  // Can manage users and most settings
        STAFF   // Can use the platform but limited settings access
    }

    // ===== ADDED: Helper methods =====
    public boolean isOwner() {
        return UserRole.OWNER.equals(role);
    }

    public boolean isAdmin() {
        return UserRole.ADMIN.equals(role) || UserRole.OWNER.equals(role);
    }

    public boolean belongsToOrganization(Long organizationId) {
        return organization != null && organization.getId().equals(organizationId);
    }
}