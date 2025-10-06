package com.reputul.backend.controllers;

import com.reputul.backend.dto.CreateEmailTemplateRequest;
import com.reputul.backend.dto.EmailTemplateDto;
import com.reputul.backend.dto.UpdateEmailTemplateRequest;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateRepository;
import com.reputul.backend.services.EmailTemplateService;
import com.reputul.backend.services.ReviewRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
@Slf4j
public class TemplateManagementController {

    private final EmailTemplateService emailTemplateService;
    private final ReviewRequestService reviewRequestService;
    private final EmailTemplateRepository emailTemplateRepository;

    /**
     * Get all templates with system vs user indication
     */
    @GetMapping
    public ResponseEntity<List<EmailTemplateDto>> getAllTemplates(@AuthenticationPrincipal User user) {
        try {
            List<EmailTemplateDto> templates = emailTemplateService.getAllTemplatesByUser(user);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            log.error("Error getting templates for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Get only user-created templates (excludes system templates)
     */
    @GetMapping("/user-created")
    public ResponseEntity<List<EmailTemplateDto>> getUserCreatedTemplates(@AuthenticationPrincipal User user) {
        try {
            List<EmailTemplateDto> templates = emailTemplateService.getUserCreatedTemplates(user);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            log.error("Error getting user templates: {}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Get only system templates
     */
    @GetMapping("/system")
    public ResponseEntity<List<EmailTemplateDto>> getSystemTemplates(@AuthenticationPrincipal User user) {
        try {
            List<EmailTemplateDto> templates = emailTemplateService.getSystemTemplates(user);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            log.error("Error getting system templates: {}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Get template statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getTemplateStats(@AuthenticationPrincipal User user) {
        try {
            Map<String, Object> stats = reviewRequestService.getTemplateStats(user);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting template stats: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Create new user template (safe - never overwrites)
     */
    @PostMapping
    public ResponseEntity<EmailTemplateDto> createTemplate(
            @AuthenticationPrincipal User user,
            @RequestBody CreateEmailTemplateRequest request) {
        try {
            EmailTemplateDto template = emailTemplateService.createTemplate(user, request);
            log.info("Created new template '{}' for user {}", template.getName(), user.getId());
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("Error creating template: {}", e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Update existing template (safe - only if user owns it)
     */
    @PutMapping("/{templateId}")
    public ResponseEntity<EmailTemplateDto> updateTemplate(
            @AuthenticationPrincipal User user,
            @PathVariable Long templateId,
            @RequestBody UpdateEmailTemplateRequest request) {
        try {
            EmailTemplateDto template = emailTemplateService.updateTemplate(user, templateId, request);
            log.info("Updated template '{}' for user {}", template.getName(), user.getId());
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("Error updating template {}: {}", templateId, e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Delete template (safe - only if user owns it, manual deletion only)
     */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Map<String, String>> deleteTemplate(
            @AuthenticationPrincipal User user,
            @PathVariable Long templateId) {
        try {
            emailTemplateService.deleteTemplate(user, templateId);
            log.info("User {} manually deleted template {}", user.getId(), templateId);
            return ResponseEntity.ok(Map.of(
                    "success", "true",
                    "message", "Template deleted successfully"
            ));
        } catch (Exception e) {
            log.error("Error deleting template {}: {}", templateId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", "false",
                    "message", "Error: " + e.getMessage()
            ));
        }
    }

    /**
     * Force update SYSTEM templates only (preserves user templates)
     */
    @PostMapping("/update-system-templates")
    public ResponseEntity<Map<String, String>> updateSystemTemplates(@AuthenticationPrincipal User user) {
        try {
            String result = reviewRequestService.forceUpdateTemplates(user);
            return ResponseEntity.ok(Map.of(
                    "success", "true",
                    "message", result
            ));
        } catch (Exception e) {
            log.error("Error updating system templates: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", "false",
                    "message", "Error: " + e.getMessage()
            ));
        }
    }

    /**
     * Get specific template by ID
     */
    @GetMapping("/{templateId}")
    public ResponseEntity<EmailTemplateDto> getTemplate(
            @AuthenticationPrincipal User user,
            @PathVariable Long templateId) {
        try {
            EmailTemplateDto template = emailTemplateService.getTemplateById(user, templateId);
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            log.error("Error getting template {}: {}", templateId, e.getMessage());
            return ResponseEntity.status(404).body(null);
        }
    }

    /**
     * Get templates by type
     */
    @GetMapping("/type/{type}")
    public ResponseEntity<List<EmailTemplateDto>> getTemplatesByType(
            @AuthenticationPrincipal User user,
            @PathVariable EmailTemplate.TemplateType type) {
        try {
            List<EmailTemplateDto> templates = emailTemplateService.getTemplatesByType(user, type);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            log.error("Error getting templates by type {}: {}", type, e.getMessage());
            return ResponseEntity.status(500).body(null);
        }
    }

    /**
     * Set template as default (safe - unsets others of same type)
     */
    @PostMapping("/{templateId}/set-default")
    public ResponseEntity<Map<String, String>> setTemplateAsDefault(
            @AuthenticationPrincipal User user,
            @PathVariable Long templateId) {
        try {
            // Get the template first
            EmailTemplateDto template = emailTemplateService.getTemplateById(user, templateId);

            // Update it to be default
            UpdateEmailTemplateRequest request = new UpdateEmailTemplateRequest();
            request.setName(template.getName());
            request.setSubject(template.getSubject());
            request.setBody(template.getBody());
            request.setType(template.getType());
            request.setIsActive(template.getIsActive());
            request.setIsDefault(true); // Set as default
            request.setAvailableVariables(template.getAvailableVariables());

            emailTemplateService.updateTemplate(user, templateId, request);

            return ResponseEntity.ok(Map.of(
                    "success", "true",
                    "message", "Template set as default successfully"
            ));
        } catch (Exception e) {
            log.error("Error setting template {} as default: {}", templateId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", "false",
                    "message", "Error: " + e.getMessage()
            ));
        }
    }

    /**
     * Preview template with sample data
     */
    @PostMapping("/{templateId}/preview")
    public ResponseEntity<Map<String, Object>> previewTemplate(
            @AuthenticationPrincipal User user,
            @PathVariable Long templateId,
            @RequestBody(required = false) Map<String, String> variableValues) {
        try {
            var preview = emailTemplateService.previewTemplate(user, templateId, variableValues);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "preview", preview
            ));
        } catch (Exception e) {
            log.error("Error previewing template {}: {}", templateId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/debug-templates")
    public ResponseEntity<Map<String, Object>> debugTemplates(@AuthenticationPrincipal User user) {
        try {
            List<EmailTemplate> allTemplates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);

            List<Map<String, Object>> templateDetails = allTemplates.stream().map(t -> {
                Map<String, Object> details = new HashMap<>();
                details.put("id", t.getId());
                details.put("name", t.getName());
                details.put("isDefault", t.getIsDefault());
                details.put("isActive", t.getIsActive());
                details.put("isHtml", t.getBody().contains("<html>"));
                details.put("hasPlaceholder", t.getBody().contains("[Review Button/Link will be inserted here]"));
                details.put("bodyPreview", t.getBody().substring(0, Math.min(100, t.getBody().length())));
                return details;
            }).collect(Collectors.toList());

            EmailTemplate defaultTemplate = emailTemplateRepository
                    .findByUserAndTypeAndIsDefaultTrue(user, EmailTemplate.TemplateType.INITIAL_REQUEST)
                    .orElse(null);

            return ResponseEntity.ok(Map.of(
                    "totalTemplates", allTemplates.size(),
                    "templates", templateDetails,
                    "currentDefault", defaultTemplate != null ? Map.of(
                            "id", defaultTemplate.getId(),
                            "name", defaultTemplate.getName(),
                            "isHtml", defaultTemplate.getBody().contains("<html>")
                    ) : "NONE"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/update-branding")
    public ResponseEntity<Map<String, String>> updateTemplateBranding(@AuthenticationPrincipal User user) {
        try {
            emailTemplateService.forceCreateDefaultTemplatesForUser(user);
            return ResponseEntity.ok(Map.of(
                    "success", "true",
                    "message", "Templates updated with official Google and Facebook branding!"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", "false",
                    "message", "Error: " + e.getMessage()
            ));
        }
    }
}