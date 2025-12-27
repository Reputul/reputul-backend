package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Table(name = "email_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "style_id")
    private EmailTemplateStyle style;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private TemplateType type;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "is_default")
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "available_variables", columnDefinition = "TEXT")
    private String availableVariables;

    // NEW: Simplified template mode
    @Column(name = "simplified_mode")
    @Builder.Default
    private Boolean simplifiedMode = false;

    @Column(name = "button_url_type", length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ButtonUrlType buttonUrlType = ButtonUrlType.FEEDBACK_GATE;

    @Column(name = "show_multiple_platforms")
    @Builder.Default
    private Boolean showMultiplePlatforms = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public enum TemplateType {
        INITIAL_REQUEST("Initial Request"),
        FOLLOW_UP_3_DAY("3-Day Follow-up"),
        FOLLOW_UP_7_DAY("7-Day Follow-up"),
        FOLLOW_UP_14_DAY("14-Day Follow-up"),
        THANK_YOU("Thank You"),
        CUSTOM("Custom");

        private final String displayName;

        TemplateType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum ButtonUrlType {
        FEEDBACK_GATE,    // Main feedback gate (routes based on rating)
        GOOGLE,           // Direct to Google reviews
        FACEBOOK,         // Direct to Facebook reviews
        YELP,             // Direct to Yelp reviews
        PRIVATE           // Private feedback only
    }
}