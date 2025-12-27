package com.reputul.backend.dto;

import com.reputul.backend.models.EmailTemplateStyle;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateStyleDto {
    private Long id;
    private Long organizationId;

    // Logo & Business Name
    private String logoUrl;
    private EmailTemplateStyle.LogoSize logoSize;
    private EmailTemplateStyle.Position logoPosition;
    private Boolean showBusinessName;
    private EmailTemplateStyle.Position businessNamePosition;

    // Custom Images
    private String customImageUrl;
    private Boolean showCustomImage;

    // Text Alignment
    private EmailTemplateStyle.Position textAlignment;

    // Button Settings
    private String buttonText;
    private EmailTemplateStyle.Position buttonAlignment;
    private EmailTemplateStyle.ButtonStyle buttonStyle;
    private String buttonColor;

    // Background & Container
    private String backgroundColor;
    private String containerBackgroundColor;
    private EmailTemplateStyle.CornerStyle containerCorners;

    // Additional Styling
    private String primaryColor;
    private String secondaryColor;
    private String textColor;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
