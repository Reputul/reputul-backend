package com.reputul.backend.dto;

import com.reputul.backend.models.EmailTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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
    private List<String> availableVariables;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}