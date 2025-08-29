package com.reputul.backend.dto;

import com.reputul.backend.models.EmailTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateDto {
    private Long id;
    private String name;
    private String subject;
    private String body;
    private EmailTemplate.TemplateType type;
    private String typeDisplayName;
    private Boolean isActive;
    private Boolean isDefault;
    private Boolean isSystemTemplate; // NEW: Indicates if this is a system template vs user-created
    private Boolean isHtml; // NEW: Indicates if template contains HTML
    private List<String> availableVariables;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Additional metadata for UI
    private Integer templateCount; // For summary views
    private String description; // For template descriptions
}