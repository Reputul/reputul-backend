package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.EmailTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService emailTemplateService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<EmailTemplateDto>> getAllTemplates(
            @RequestParam(required = false, defaultValue = "false") boolean activeOnly,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        List<EmailTemplateDto> templates = activeOnly
                ? emailTemplateService.getActiveTemplatesByUser(user)
                : emailTemplateService.getAllTemplatesByUser(user);

        return ResponseEntity.ok(templates);
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<EmailTemplateDto>> getTemplatesByType(
            @PathVariable EmailTemplate.TemplateType type,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<EmailTemplateDto> templates = emailTemplateService.getTemplatesByType(user, type);
        return ResponseEntity.ok(templates);
    }

    @GetMapping("/{templateId}")
    public ResponseEntity<EmailTemplateDto> getTemplateById(
            @PathVariable Long templateId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            EmailTemplateDto template = emailTemplateService.getTemplateById(user, templateId);
            return ResponseEntity.ok(template);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/default/{type}")
    public ResponseEntity<EmailTemplateDto> getDefaultTemplate(
            @PathVariable EmailTemplate.TemplateType type,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            EmailTemplateDto template = emailTemplateService.getDefaultTemplate(user, type);
            return ResponseEntity.ok(template);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<EmailTemplateDto> createTemplate(
            @RequestBody CreateEmailTemplateRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            EmailTemplateDto template = emailTemplateService.createTemplate(user, request);
            return ResponseEntity.ok(template);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{templateId}")
    public ResponseEntity<EmailTemplateDto> updateTemplate(
            @PathVariable Long templateId,
            @RequestBody UpdateEmailTemplateRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            EmailTemplateDto template = emailTemplateService.updateTemplate(user, templateId, request);
            return ResponseEntity.ok(template);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable Long templateId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            emailTemplateService.deleteTemplate(user, templateId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{templateId}/preview")
    public ResponseEntity<EmailTemplatePreviewDto> previewTemplate(
            @PathVariable Long templateId,
            @RequestBody(required = false) Map<String, String> variableValues,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            EmailTemplatePreviewDto preview = emailTemplateService.previewTemplate(user, templateId, variableValues);
            return ResponseEntity.ok(preview);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/preview")
    public ResponseEntity<EmailTemplatePreviewDto> previewTemplateContent(
            @RequestBody EmailTemplatePreviewRequest request) {
        EmailTemplatePreviewDto preview = emailTemplateService.previewTemplateContent(
                request.getSubject(),
                request.getBody(),
                request.getVariableValues()
        );
        return ResponseEntity.ok(preview);
    }

    @GetMapping("/search")
    public ResponseEntity<List<EmailTemplateDto>> searchTemplates(
            @RequestParam String q,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<EmailTemplateDto> templates = emailTemplateService.searchTemplates(user, q);
        return ResponseEntity.ok(templates);
    }

    @PostMapping("/init-defaults")
    public ResponseEntity<Void> initializeDefaultTemplates(Authentication authentication) {
        User user = getCurrentUser(authentication);
        emailTemplateService.createDefaultTemplatesForUser(user);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/types")
    public ResponseEntity<List<TemplateTypeInfo>> getTemplateTypes() {
        List<TemplateTypeInfo> types = List.of(
                new TemplateTypeInfo(EmailTemplate.TemplateType.INITIAL_REQUEST, "Initial Review Request"),
                new TemplateTypeInfo(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY, "3-Day Follow-up"),
                new TemplateTypeInfo(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY, "7-Day Follow-up"),
                new TemplateTypeInfo(EmailTemplate.TemplateType.FOLLOW_UP_14_DAY, "14-Day Follow-up"),
                new TemplateTypeInfo(EmailTemplate.TemplateType.THANK_YOU, "Thank You"),
                new TemplateTypeInfo(EmailTemplate.TemplateType.CUSTOM, "Custom Template")
        );
        return ResponseEntity.ok(types);
    }

    @GetMapping("/variables")
    public ResponseEntity<List<TemplateVariable>> getAvailableVariables() {
        List<TemplateVariable> variables = List.of(
                new TemplateVariable("customerName", "Customer's full name"),
                new TemplateVariable("businessName", "Your business name"),
                new TemplateVariable("serviceType", "Type of service provided"),
                new TemplateVariable("serviceDate", "Date when service was provided"),
                new TemplateVariable("businessPhone", "Your business phone number"),
                new TemplateVariable("businessWebsite", "Your business website URL")
        );
        return ResponseEntity.ok(variables);
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // Helper DTOs for API responses
    public static class TemplateTypeInfo {
        public EmailTemplate.TemplateType type;
        public String displayName;

        public TemplateTypeInfo(EmailTemplate.TemplateType type, String displayName) {
            this.type = type;
            this.displayName = displayName;
        }
    }

    public static class TemplateVariable {
        public String variable;
        public String description;

        public TemplateVariable(String variable, String description) {
            this.variable = variable;
            this.description = description;
        }
    }
}