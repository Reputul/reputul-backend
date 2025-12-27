package com.reputul.backend.controllers;

import com.reputul.backend.dto.EmailTemplateStyleDto;
import com.reputul.backend.dto.UpdateEmailTemplateStyleRequest;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.EmailTemplateStyleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/email-template-styles")
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateStyleController {

    private final EmailTemplateStyleService styleService;
    private final UserRepository userRepository;

    /**
     * Get the authenticated user from SecurityContext
     */
    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuntimeException("User not authenticated");
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
    }

    /**
     * Get the organization's email template style
     * GET /api/v1/email-template-styles
     */
    @GetMapping
    public ResponseEntity<EmailTemplateStyleDto> getOrganizationStyle() {
        User user = getAuthenticatedUser();
        log.info("User {} fetching organization email template style", user.getId());
        EmailTemplateStyleDto style = styleService.getOrganizationStyle(user);
        return ResponseEntity.ok(style);
    }

    /**
     * Update the organization's email template style
     * PUT /api/v1/email-template-styles
     */
    @PutMapping
    public ResponseEntity<EmailTemplateStyleDto> updateOrganizationStyle(
            @RequestBody UpdateEmailTemplateStyleRequest request) {
        User user = getAuthenticatedUser();
        log.info("User {} updating organization email template style", user.getId());
        EmailTemplateStyleDto style = styleService.createOrUpdateStyle(user, request);
        return ResponseEntity.ok(style);
    }

    /**
     * Reset organization's email template style to defaults
     * POST /api/v1/email-template-styles/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> resetToDefaults() {
        User user = getAuthenticatedUser();
        log.info("User {} resetting email template style to defaults", user.getId());
        styleService.resetToDefaults(user);
        return ResponseEntity.ok().build();
    }
}