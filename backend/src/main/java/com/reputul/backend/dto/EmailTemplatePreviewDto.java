package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplatePreviewDto {
    private String subject;
    private String body;
    private Map<String, String> variableValues;
    private String renderedSubject;
    private String renderedBody;
}