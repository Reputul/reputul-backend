package com.reputul.backend.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_template_styles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateStyle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    // Logo & Business Name
    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "logo_size", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private LogoSize logoSize = LogoSize.SMALL;

    @Column(name = "logo_position", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Position logoPosition = Position.LEFT;

    @Column(name = "show_business_name")
    @Builder.Default
    private Boolean showBusinessName = true;

    @Column(name = "business_name_position", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Position businessNamePosition = Position.CENTER;

    // Custom Images
    @Column(name = "custom_image_url", length = 500)
    private String customImageUrl;

    @Column(name = "show_custom_image")
    @Builder.Default
    private Boolean showCustomImage = false;

    // Text Alignment
    @Column(name = "text_alignment", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Position textAlignment = Position.LEFT;

    // Button Settings
    @Column(name = "button_text", length = 100)
    @Builder.Default
    private String buttonText = "Leave Feedback";

    @Column(name = "button_alignment", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Position buttonAlignment = Position.CENTER;

    @Column(name = "button_style", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ButtonStyle buttonStyle = ButtonStyle.ROUNDED;

    @Column(name = "button_color", length = 7)
    @Builder.Default
    private String buttonColor = "#00D682";

    // Background & Container
    @Column(name = "background_color", length = 7)
    @Builder.Default
    private String backgroundColor = "#F2F2F7";

    @Column(name = "container_background_color", length = 7)
    @Builder.Default
    private String containerBackgroundColor = "#FFFFFF";

    @Column(name = "container_corners", length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CornerStyle containerCorners = CornerStyle.ROUNDED;

    // Additional Styling
    @Column(name = "primary_color", length = 7)
    @Builder.Default
    private String primaryColor = "#00D682";

    @Column(name = "secondary_color", length = 7)
    @Builder.Default
    private String secondaryColor = "#333333";

    @Column(name = "text_color", length = 7)
    @Builder.Default
    private String textColor = "#333333";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Enums for type safety
    public enum LogoSize {
        SMALL, MEDIUM, LARGE
    }

    public enum Position {
        LEFT, CENTER, RIGHT
    }

    public enum ButtonStyle {
        ROUNDED, PILL
    }

    public enum CornerStyle {
        ROUNDED, SHARP
    }
}