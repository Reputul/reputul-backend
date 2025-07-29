package com.reputul.backend.dto;

import com.reputul.backend.models.EmailTemplate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEmailTemplateRequest {
    private String name;
    private String subject;
    private String body;
    private EmailTemplate.TemplateType type;
    private Boolean isActive = true;
    private Boolean isDefault = false;
    private List<String> availableVariables;
}