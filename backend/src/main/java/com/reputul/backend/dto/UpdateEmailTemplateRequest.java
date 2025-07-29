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
public class UpdateEmailTemplateRequest {
    private String name;
    private String subject;
    private String body;
    private EmailTemplate.TemplateType type;
    private Boolean isActive;
    private Boolean isDefault;
    private List<String> availableVariables;
}
// This class is used to update an existing email template with new values.