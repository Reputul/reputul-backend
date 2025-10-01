package com.reputul.backend.services;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateService {

    @PersistenceContext
    private EntityManager entityManager;
    private final EmailTemplateRepository emailTemplateRepository;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    // GOOGLE COMPLIANT: Main method to safely update user templates
    @Transactional
    public void safeUpdateUserTemplates(User user) {
        log.info("🔄 COMPLIANT template update for user: {}", user.getEmail());

        try {
            // Step 1: Get system template names to identify what to replace
            List<String> systemTemplateNames = Arrays.asList(
                    "Google Compliant Review Request",
                    "Google Compliant Multi-Platform Request",
                    "Google Compliant 3-Day Follow-up",
                    "Google Compliant 7-Day Follow-up",
                    "Google Compliant 14-Day Follow-up",
                    "Google Compliant Thank You",
                    // Legacy names to replace
                    "Review Request Email",
                    "Multi-Platform Review Request",
                    "3-Day Follow-up",
                    "7-Day Follow-up",
                    "14-Day Follow-up",
                    "Thank You for Your Review"
            );

            // Step 2: Find old templates to delete
            List<EmailTemplate> oldTemplates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                    .stream()
                    .filter(template -> systemTemplateNames.contains(template.getName()))
                    .collect(Collectors.toList());

            log.info("Found {} old templates to replace with Google-compliant versions", oldTemplates.size());

            // Step 3: Delete old templates in separate transactions
            for (EmailTemplate oldTemplate : oldTemplates) {
                deleteTemplateInSeparateTransaction(oldTemplate.getId());
            }

            // Step 4: Create new Google-compliant templates
            createGoogleCompliantTemplates(user);

            log.info("✅ Successfully updated templates to Google-compliant versions for user: {}", user.getEmail());

        } catch (Exception e) {
            log.error("❌ Error updating compliant templates for user {}: {}", user.getEmail(), e.getMessage());
            throw new RuntimeException("Failed to update compliant templates for user: " + user.getEmail(), e);
        }
    }

    // Delete template in separate transaction to avoid rollback issues
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteTemplateInSeparateTransaction(Long templateId) {
        try {
            emailTemplateRepository.findById(templateId).ifPresent(template -> {
                log.info("Deleting old template: {} (ID: {})", template.getName(), templateId);
                emailTemplateRepository.delete(template);
            });
        } catch (DataIntegrityViolationException e) {
            log.warn("Cannot delete template ID {} - it's referenced by review requests", templateId);
        } catch (Exception e) {
            log.error("Error deleting template ID {}: {}", templateId, e.getMessage());
        }
    }

    // Standard template methods (unchanged)
    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getAllTemplatesByUser(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .map(template -> {
                    EmailTemplateDto dto = convertToDto(template);
                    dto.setIsSystemTemplate(isSystemTemplate(template.getName()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getActiveTemplatesByUser(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserAndIsActiveTrueOrderByCreatedAtDesc(user);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getTemplatesByType(User user, EmailTemplate.TemplateType type) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserAndTypeOrderByCreatedAtDesc(user, type);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public EmailTemplateDto getTemplateById(User user, Long templateId) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        return convertToDto(template);
    }

    @Transactional
    public EmailTemplateDto getDefaultTemplate(User user, EmailTemplate.TemplateType type) {
        Optional<EmailTemplate> defaultTemplateOpt = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type);

        if (defaultTemplateOpt.isPresent()) {
            return convertToDto(defaultTemplateOpt.get());
        }

        // No default found, create Google-compliant templates
        createDefaultTemplatesForUser(user);

        // Try again after creating templates
        EmailTemplate template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type)
                .orElseThrow(() -> new RuntimeException("No default template found for type: " + type));

        return convertToDto(template);
    }

    @Transactional
    public EmailTemplateDto createTemplate(User user, CreateEmailTemplateRequest request) {
        if (request.getIsDefault() != null && request.getIsDefault()) {
            unsetDefaultsForType(user, request.getType());
        }

        EmailTemplate template = EmailTemplate.builder()
                .name(request.getName())
                .subject(request.getSubject())
                .body(request.getBody())
                .type(request.getType())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .isDefault(request.getIsDefault() != null ? request.getIsDefault() : false)
                .availableVariables(convertVariablesToString(request.getAvailableVariables()))
                .user(user)
                .build();

        EmailTemplate savedTemplate = emailTemplateRepository.save(template);
        log.info("Created new user template '{}' for user {}", savedTemplate.getName(), user.getId());
        return convertToDto(savedTemplate);
    }

    @Transactional
    public EmailTemplateDto updateTemplate(User user, Long templateId, UpdateEmailTemplateRequest request) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found or access denied"));

        if (request.getIsDefault() != null && request.getIsDefault() &&
                (!template.getIsDefault() || !template.getType().equals(request.getType()))) {
            unsetDefaultsForType(user, request.getType());
        }

        template.setName(request.getName());
        template.setSubject(request.getSubject());
        template.setBody(request.getBody());
        template.setType(request.getType());
        template.setIsActive(request.getIsActive());
        template.setIsDefault(request.getIsDefault());
        template.setAvailableVariables(convertVariablesToString(request.getAvailableVariables()));

        EmailTemplate savedTemplate = emailTemplateRepository.save(template);
        log.info("Updated template '{}' for user {}", savedTemplate.getName(), user.getId());
        return convertToDto(savedTemplate);
    }

    @Transactional
    public void deleteTemplate(User user, Long templateId) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found or access denied"));

        log.info("User {} manually deleting template '{}' (ID: {})", user.getId(), template.getName(), templateId);
        emailTemplateRepository.delete(template);
    }

    @Transactional
    public void createDefaultTemplatesForUser(User user) {
        boolean hasCompliantTemplates = hasGoogleCompliantTemplates(user);

        if (!hasCompliantTemplates) {
            log.info("Creating Google-compliant templates for user {} (no compliant templates found)", user.getId());
            createGoogleCompliantTemplates(user);
        } else {
            log.info("User {} already has Google-compliant templates, skipping creation", user.getId());
        }
    }

    // BACKWARD COMPATIBILITY: Method for ReviewRequestService
    @Transactional
    public void forceCreateDefaultTemplatesForUser(User user) {
        log.info("Force updating templates to Google-compliant for user {} (called from ReviewRequestService)", user.getEmail());
        safeUpdateUserTemplates(user);
    }

    @Transactional(readOnly = true)
    public EmailTemplatePreviewDto previewTemplate(User user, Long templateId, Map<String, String> variableValues) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        return generatePreview(template, variableValues);
    }

    @Transactional(readOnly = true)
    public EmailTemplatePreviewDto previewTemplateContent(String subject, String body, Map<String, String> variableValues) {
        EmailTemplate tempTemplate = EmailTemplate.builder()
                .subject(subject)
                .body(body)
                .build();

        return generatePreview(tempTemplate, variableValues);
    }

    @Transactional(readOnly = true)
    public String renderTemplate(User user, Long templateId, Customer customer) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Map<String, String> variables = createCompliantVariableMapFromCustomer(customer);
        return replaceVariables(template.getBody(), variables);
    }

    @Transactional(readOnly = true)
    public String renderTemplateSubject(User user, Long templateId, Customer customer) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Map<String, String> variables = createCompliantVariableMapFromCustomer(customer);
        return replaceVariables(template.getSubject(), variables);
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> searchTemplates(User user, String searchTerm) {
        List<EmailTemplate> templates = emailTemplateRepository.searchTemplates(user, searchTerm);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getUserCreatedTemplates(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .filter(template -> !isSystemTemplate(template.getName()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateDto> getSystemTemplates(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .filter(template -> isSystemTemplate(template.getName()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public String processTemplateWithCustomer(Customer customer) {
        try {
            EmailTemplate template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(
                            customer.getBusiness().getUser(), EmailTemplate.TemplateType.INITIAL_REQUEST)
                    .orElseThrow(() -> new RuntimeException("No default template found"));

            Map<String, String> variables = createCompliantVariableMapFromCustomer(customer);
            return replaceVariables(template.getBody(), variables);

        } catch (Exception e) {
            // Fallback to a compliant email if template fails
            return createCompliantFallbackEmail(customer);
        }
    }

    // GOOGLE COMPLIANT: Create modern, Google-compliant templates
    @Transactional
    public void createGoogleCompliantTemplates(User user) {
        List<EmailTemplate> templates = Arrays.asList(
                // 1. Google Compliant Initial Request Template
                EmailTemplate.builder()
                        .name("Google Compliant Review Request")
                        .subject("We'd love your honest feedback, {{customerName}}!")
                        .body(createGoogleCompliantInitialRequestTemplate())
                        .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 2. Google Compliant Multi-Platform Request Template
                EmailTemplate.builder()
                        .name("Google Compliant Multi-Platform Request")
                        .subject("Share your experience - {{businessName}}")
                        .body(createGoogleCompliantInitialRequestTemplate())
                        .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                        .isActive(true)
                        .isDefault(false)
                        .user(user)
                        .build(),

                // 3. Google Compliant 3-Day Follow-up
                EmailTemplate.builder()
                        .name("Google Compliant 3-Day Follow-up")
                        .subject("Quick check-in: How was your {{serviceType}} experience?")
                        .body(createGoogleCompliant3DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 4. Google Compliant 7-Day Follow-up
                EmailTemplate.builder()
                        .name("Google Compliant 7-Day Follow-up")
                        .subject("Your opinion matters - {{businessName}}")
                        .body(createGoogleCompliant7DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 5. Google Compliant 14-Day Follow-up
                EmailTemplate.builder()
                        .name("Google Compliant 14-Day Follow-up")
                        .subject("Final request: Share your {{businessName}} experience")
                        .body(createGoogleCompliant14DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_14_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 6. Google Compliant Thank You Template
                EmailTemplate.builder()
                        .name("Google Compliant Thank You")
                        .subject("Thank you so much, {{customerName}}!")
                        .body(createGoogleCompliantThankYouTemplate())
                        .type(EmailTemplate.TemplateType.THANK_YOU)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build()
        );

        emailTemplateRepository.saveAll(templates);
        log.info("✅ Created {} Google-compliant templates for user {}", templates.size(), user.getId());
    }

    // Get user template statistics
    @Transactional(readOnly = true)
    public Map<String, Object> getUserTemplateStats(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTemplates", templates.size());
        stats.put("systemTemplates", templates.stream().filter(t -> isSystemTemplate(t.getName())).count());
        stats.put("userTemplates", templates.stream().filter(t -> !isSystemTemplate(t.getName())).count());
        stats.put("activeTemplates", templates.stream().filter(EmailTemplate::getIsActive).count());
        stats.put("defaultTemplates", templates.stream().filter(EmailTemplate::getIsDefault).count());
        stats.put("googleCompliantTemplates", templates.stream().filter(t -> isGoogleCompliantTemplate(t.getName())).count());

        return stats;
    }

    // GOOGLE COMPLIANT TEMPLATE CREATION METHODS

    /**
     * GOOGLE COMPLIANT: Initial request template - Always shows ALL platforms to ALL customers
     */
    private String createGoogleCompliantInitialRequestTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Share Your Experience</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.4; color: #333333; background-color: #f0f0f0;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f0f0f0; min-height: 100%;">
                <tr>
                    <td align="center" style="padding: 20px;">
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #4a90e2; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">We value your honest feedback</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px;">
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%">
                                        <tr>
                                            <td style="text-align: center; padding-bottom: 20px;">
                                                <span style="font-size: 40px;">⭐</span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td>
                                                <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                                <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">Thank you for choosing {{businessName}} for your {{serviceType}} on {{serviceDate}}.</p>
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">We hope you were satisfied with our service. Your honest feedback - whether positive or negative - helps us improve and assists other customers in making informed decisions.</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- GOOGLE COMPLIANT: All Review Options Always Available -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f8f9fa; border-radius: 8px; border: 1px solid #e9ecef;">
                                        <tr>
                                            <td style="padding: 30px 20px; text-align: center;">
                                                <h2 style="margin: 0 0 10px 0; color: #333333; font-size: 20px; font-weight: bold;">Share Your Honest Experience</h2>
                                                <p style="margin: 0 0 25px 0; color: #666666; font-size: 14px;">Choose whichever platform you prefer - all options are always available:</p>
                                                
                                                <!-- Google Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 15px auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{googleReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ffffff; border: 2px solid #4285f4; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #4285f4; font-weight: bold; font-size: 16px;">⭐ Google Review</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                                
                                                <!-- Facebook Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 15px auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{facebookReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ffffff; border: 2px solid #1877f2; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #1877f2; font-weight: bold; font-size: 16px;">👍 Facebook Review</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                                
                                                <!-- Yelp Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 15px auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{yelpReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ffffff; border: 2px solid #d32323; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #d32323; font-weight: bold; font-size: 16px;">⭐ Yelp Review</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                                
                                                <!-- Private Feedback Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{privateFeedbackUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #4a90e2; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #ffffff; font-weight: bold; font-size: 16px;">💬 Private Feedback</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>

                                                <p style="margin: 20px 0 0 0; color: #666666; font-size: 12px;">
                                                    ✅ All feedback options are always available. We appreciate your honest review.
                                                </p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #666666; font-size: 13px; text-align: center;">
                                        Your honest feedback helps us improve and helps other customers make informed decisions.
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #f8f9fa; padding: 25px 30px; border-top: 1px solid #dee2e6; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0 0 5px 0; font-weight: bold; color: #333333; font-size: 16px;">{{businessName}}</p>
                                    <p style="margin: 0 0 5px 0; font-size: 14px; color: #666666;">{{businessPhone}}</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">{{businessWebsite}}</p>
                                    <p style="margin: 0; font-size: 12px; color: #999999;">
                                        <a href="{{unsubscribeUrl}}" style="color: #666666; text-decoration: none;">Unsubscribe</a>
                                    </p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """;
    }

    private String createGoogleCompliant3DayFollowUpTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Quick Follow-up</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.4; color: #333333; background-color: #fff8e1;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #fff8e1; min-height: 100%;">
                <tr>
                    <td align="center" style="padding: 20px;">
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px;">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #ff9800; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">Quick check-in</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px;">
                                    <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been a few days since we completed your {{serviceType}} service. We hope everything is going well!</p>
                                    <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">If you have a moment, we'd really appreciate your honest feedback about your experience with us.</p>
                                    
                                    <!-- COMPLIANT: All Review Options -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #fff3c4; border-radius: 8px; padding: 25px; text-align: center;">
                                        <tr>
                                            <td>
                                                <h3 style="margin: 0 0 15px 0; color: #e65100;">Share Your Experience</h3>
                                                <p style="margin: 0 0 20px 0; color: #f57f17; font-size: 14px;">All platforms are always available:</p>
                                                
                                                <!-- All platform buttons with equal treatment -->
                                                <div style="margin-bottom: 10px;">
                                                    <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 10px 20px; text-decoration: none; border-radius: 5px; margin: 5px; border: 2px solid #ffcc02;">⭐ Google</a>
                                                    <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 10px 20px; text-decoration: none; border-radius: 5px; margin: 5px; border: 2px solid #ffcc02;">👍 Facebook</a>
                                                </div>
                                                <div>
                                                    <a href="{{yelpReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 10px 20px; text-decoration: none; border-radius: 5px; margin: 5px; border: 2px solid #ffcc02;">⭐ Yelp</a>
                                                    <a href="{{privateFeedbackUrl}}" style="display: inline-block; background-color: #ff9800; color: white; padding: 10px 20px; text-decoration: none; border-radius: 5px; margin: 5px;">💬 Private</a>
                                                </div>
                                            </td>
                                        </tr>
                                    </table>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #fffbf0; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0; font-size: 14px; color: #666666;">{{businessName}} • {{businessPhone}} • {{businessWebsite}}</p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """;
    }

    private String createGoogleCompliant7DayFollowUpTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>One Week Follow-up</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.4; color: #333333; background-color: #ffebee;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #ffebee; min-height: 100%;">
                <tr>
                    <td align="center" style="padding: 20px;">
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px;">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #d32f2f; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">We'd love your feedback</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px; text-align: center;">
                                    <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been a week since your {{serviceType}} service. We hope everything is still working perfectly!</p>
                                    <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">We'd be incredibly grateful for your honest feedback about your experience with us.</p>
                                    
                                    <!-- COMPLIANT: Equal treatment for all platforms -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #ffebee; border-radius: 8px; padding: 20px;">
                                        <tr>
                                            <td style="text-align: center;">
                                                <h3 style="margin: 0 0 15px 0; color: #c62828;">Your Opinion Matters! 💭</h3>
                                                <p style="margin: 0 0 15px 0; color: #d32f2f; font-size: 14px;">All review options are equally available:</p>
                                                
                                                <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 12px 24px; text-decoration: none; border-radius: 6px; margin: 8px; border: 2px solid #d32f2f; font-weight: bold;">⭐ Google Review</a>
                                                <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 12px 24px; text-decoration: none; border-radius: 6px; margin: 8px; border: 2px solid #d32f2f; font-weight: bold;">👍 Facebook Review</a>
                                                <br>
                                                <a href="{{yelpReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #333; padding: 12px 24px; text-decoration: none; border-radius: 6px; margin: 8px; border: 2px solid #d32f2f; font-weight: bold;">⭐ Yelp Review</a>
                                                <a href="{{privateFeedbackUrl}}" style="display: inline-block; background-color: #d32f2f; color: white; padding: 12px 24px; text-decoration: none; border-radius: 6px; margin: 8px; font-weight: bold;">💬 Private Feedback</a>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #d32f2f; font-size: 13px;">
                                        Your honest feedback helps us improve and helps other customers make informed decisions.
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #ffebee; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0; font-size: 14px; color: #666666;">{{businessName}} • {{businessPhone}} • {{businessWebsite}}</p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """;
    }

    private String createGoogleCompliant14DayFollowUpTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Final Follow-up</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.4; color: #333333; background-color: #f3e5f5;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f3e5f5; min-height: 100%;">
                <tr>
                    <td align="center" style="padding: 20px;">
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px;">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #7b1fa2; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">Final follow-up</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px; text-align: center;">
                                    <span style="font-size: 40px; display: block; margin-bottom: 20px;">🙏</span>
                                    <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been two weeks since your {{serviceType}} service.</p>
                                    <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">This will be our final follow-up. If you have a spare minute, we'd be tremendously grateful for your honest review. It really helps our small business!</p>
                                    
                                    <!-- COMPLIANT: Final request shows all options -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f3e5f5; border-radius: 8px; padding: 25px;">
                                        <tr>
                                            <td style="text-align: center;">
                                                <h3 style="margin: 0 0 10px 0; color: #4a148c;">Share Your Experience 🙏</h3>
                                                <p style="margin: 0 0 20px 0; color: #7b1fa2; font-size: 14px;">All platforms are available - choose what works for you:</p>
                                                
                                                <div style="margin-bottom: 15px;">
                                                    <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #7b1fa2; color: white; padding: 12px 25px; text-decoration: none; border-radius: 6px; margin: 5px; font-weight: bold; font-size: 16px;">⭐ Google Review</a>
                                                </div>
                                                <div style="margin-bottom: 15px;">
                                                    <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #7b1fa2; padding: 12px 25px; text-decoration: none; border-radius: 6px; margin: 5px; border: 2px solid #7b1fa2; font-weight: bold; font-size: 16px;">👍 Facebook Review</a>
                                                </div>
                                                <div style="margin-bottom: 15px;">
                                                    <a href="{{yelpReviewUrl}}" style="display: inline-block; background-color: #ffffff; color: #7b1fa2; padding: 12px 25px; text-decoration: none; border-radius: 6px; margin: 5px; border: 2px solid #7b1fa2; font-weight: bold; font-size: 16px;">⭐ Yelp Review</a>
                                                </div>
                                                <div>
                                                    <a href="{{privateFeedbackUrl}}" style="display: inline-block; background-color: #ffffff; color: #7b1fa2; padding: 12px 25px; text-decoration: none; border-radius: 6px; margin: 5px; border: 2px solid #7b1fa2; font-weight: bold; font-size: 16px;">💬 Private Feedback</a>
                                                </div>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #7b1fa2; font-size: 13px;">
                                        Thank you for being a valued customer. We hope to serve you again!
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #f8f9fa; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0; font-size: 14px; color: #666666;">{{businessName}} • {{businessPhone}} • {{businessWebsite}}</p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """;
    }

    private String createGoogleCompliantThankYouTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Thank You!</title>
        </head>
        <body style="margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.4; color: #333333; background-color: #e8f5e8;">
            <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #e8f5e8; min-height: 100%;">
                <tr>
                    <td align="center" style="padding: 20px;">
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px;">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #2e7d32; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">Thank you so much!</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px; text-align: center;">
                                    <span style="font-size: 60px; display: block; margin-bottom: 30px;">🎉</span>
                                    
                                    <h2 style="margin: 0 0 20px 0; font-size: 28px; color: #1b5e20; font-weight: bold;">Thank You, {{customerName}}!</h2>
                                    
                                    <p style="margin: 0 0 15px 0; font-size: 18px; color: #333333;">We received your review and we're absolutely thrilled!</p>
                                    <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">Your honest feedback about our {{serviceType}} service means the world to us and helps other customers make informed decisions.</p>
                                    
                                    <!-- Why Reviews Matter Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #e8f5e8; border-radius: 8px; padding: 25px;">
                                        <tr>
                                            <td style="text-align: center;">
                                                <h3 style="margin: 0 0 20px 0; color: #2e7d32; font-size: 18px; font-weight: bold;">Why Your Review Matters</h3>
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto;">
                                                    <tr><td style="padding: 5px 0; color: #1b5e20; font-size: 14px;"><span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Helps other customers find us</td></tr>
                                                    <tr><td style="padding: 5px 0; color: #1b5e20; font-size: 14px;"><span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Supports our small business</td></tr>
                                                    <tr><td style="padding: 5px 0; color: #1b5e20; font-size: 14px;"><span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Motivates our entire team</td></tr>
                                                    <tr><td style="padding: 5px 0; color: #1b5e20; font-size: 14px;"><span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Helps us improve our service</td></tr>
                                                </table>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 30px 0 20px 0; color: #2e7d32; font-size: 16px; font-weight: bold;">
                                        We appreciate you taking the time to share your honest experience!
                                    </p>
                                    
                                    <!-- Future Service Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f8f9fa; border-radius: 8px; padding: 20px;">
                                        <tr>
                                            <td style="text-align: center;">
                                                <h3 style="margin: 0 0 10px 0; color: #333333; font-size: 18px; font-weight: bold;">Need Future Service?</h3>
                                                <p style="margin: 0 0 15px 0; color: #666666; font-size: 14px;">We're always here to help when you need us again.</p>
                                                <p style="margin: 0 0 5px 0; font-weight: bold; color: #333333; font-size: 16px;">{{businessPhone}}</p>
                                                <p style="margin: 0; font-size: 14px; color: #666666;">{{businessWebsite}}</p>
                                            </td>
                                        </tr>
                                    </table>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #2e7d32; color: #ffffff; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0 0 5px 0; font-size: 18px; font-weight: bold; color: #ffffff;">{{businessName}} Team</p>
                                    <p style="margin: 0; font-size: 14px; color: #ffffff;">Committed to excellence in every service</p>
                                </td>
                            </tr>
                        </table>
                    </td>
                </tr>
            </table>
        </body>
        </html>
        """;
    }

    // Helper methods for preview and rendering
    private EmailTemplatePreviewDto generatePreview(EmailTemplate template, Map<String, String> variableValues) {
        Map<String, String> variables = variableValues != null ? variableValues : createCompliantSampleVariables();

        String renderedSubject = replaceVariables(template.getSubject(), variables);
        String renderedBody = replaceVariables(template.getBody(), variables);

        return EmailTemplatePreviewDto.builder()
                .subject(template.getSubject())
                .body(template.getBody())
                .variableValues(variables)
                .renderedSubject(renderedSubject)
                .renderedBody(renderedBody)
                .build();
    }

    // GOOGLE COMPLIANT: Create sample variables with all platform URLs
    private Map<String, String> createCompliantSampleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", "John Smith");
        variables.put("businessName", "ABC Home Services");
        variables.put("serviceType", "Kitchen Sink Repair");
        variables.put("serviceDate", "January 15, 2025");
        variables.put("businessPhone", "(555) 123-4567");
        variables.put("businessWebsite", "www.abchomeservices.com");

        // ✅ GOOGLE COMPLIANT: All platform URLs always available
        variables.put("googleReviewUrl", "https://search.google.com/local/writereview?placeid=sample_place_id");
        variables.put("facebookReviewUrl", "https://facebook.com/abchomeservices/reviews");
        variables.put("yelpReviewUrl", "https://yelp.com/biz/abc-home-services");
        variables.put("privateFeedbackUrl", "https://reputul.com/feedback/sample123");
        variables.put("unsubscribeUrl", "https://reputul.com/unsubscribe/sample123");

        // Additional variables
        variables.put("reviewLink", "https://reputul.com/feedback-gate/sample123");
        variables.put("supportEmail", "support@abchomeservices.com");
        variables.put("businessAddress", "123 Main St, Springfield, IL 62701");

        return variables;
    }

    // GOOGLE COMPLIANT: Create compliant fallback email
    private String createCompliantFallbackEmail(Customer customer) {
        Business business = customer.getBusiness();
        String googleUrl = generateGoogleReviewUrl(business);
        String facebookUrl = generateFacebookReviewUrl(business);
        String yelpUrl = generateYelpReviewUrl(business);
        String privateFeedbackUrl = frontendUrl + "/feedback/" + customer.getId();

        return String.format("""
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2>%s</h2>
                <p>Hi %s,</p>
                <p>Thank you for choosing %s for your %s service on %s.</p>
                <p>We'd appreciate your honest feedback. All review options are always available:</p>
                <div style="text-align: center; margin: 20px 0;">
                    <a href="%s" style="background-color: #ffffff; color: #374151; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px; border: 2px solid #4285f4;">
                        <span style="color: #4285F4; font-weight: bold; margin-right: 8px;">⭐</span>Google Review
                    </a>
                    <a href="%s" style="background-color: #ffffff; color: #374151; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px; border: 2px solid #1877f2;">
                        <span style="color: #1877f2; font-weight: bold; margin-right: 8px;">👍</span>Facebook Review
                    </a>
                    <br>
                    <a href="%s" style="background-color: #ffffff; color: #374151; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px; border: 2px solid #d32323;">
                        <span style="color: #d32323; font-weight: bold; margin-right: 8px;">⭐</span>Yelp Review
                    </a>
                    <a href="%s" style="background-color: #6b7280; color: white; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px;">
                        <span style="font-weight: bold; margin-right: 8px;">💬</span>Private Feedback
                    </a>
                </div>
                <p style="font-size: 12px; color: #666; text-align: center;">✅ All options are always available to ensure Google compliance</p>
                <p>Best regards,<br>%s Team</p>
            </body>
            </html>
            """,
                business.getName() != null ? business.getName() : "Thank You",
                customer.getName() != null ? customer.getName() : "Valued Customer",
                business.getName() != null ? business.getName() : "Our Business",
                customer.getServiceType() != null ? customer.getServiceType() : "recent",
                customer.getServiceDate() != null ? customer.getServiceDate().toString() : "recently",
                googleUrl, facebookUrl, yelpUrl, privateFeedbackUrl,
                business.getName() != null ? business.getName() : "Our Business"
        );
    }

    // Helper methods
    private boolean hasGoogleCompliantTemplates(User user) {
        List<String> compliantTemplateNames = Arrays.asList(
                "Google Compliant Review Request", "Google Compliant Multi-Platform Request",
                "Google Compliant 3-Day Follow-up", "Google Compliant 7-Day Follow-up",
                "Google Compliant 14-Day Follow-up", "Google Compliant Thank You"
        );

        return emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .anyMatch(template ->
                        compliantTemplateNames.contains(template.getName()) &&
                                template.getBody() != null &&
                                (template.getBody().contains("<html>") || template.getBody().contains("<!DOCTYPE"))
                );
    }

    private boolean isSystemTemplate(String templateName) {
        List<String> systemTemplateNames = Arrays.asList(
                "Google Compliant Review Request", "Google Compliant Multi-Platform Request",
                "Google Compliant 3-Day Follow-up", "Google Compliant 7-Day Follow-up",
                "Google Compliant 14-Day Follow-up", "Google Compliant Thank You",
                // Legacy names
                "Review Request Email", "Multi-Platform Review Request", "3-Day Follow-up",
                "7-Day Follow-up", "14-Day Follow-up", "Thank You for Your Review"
        );
        return systemTemplateNames.contains(templateName);
    }

    private boolean isGoogleCompliantTemplate(String templateName) {
        return templateName != null && templateName.startsWith("Google Compliant");
    }

    private void unsetDefaultsForType(User user, EmailTemplate.TemplateType type) {
        List<EmailTemplate> allTemplatesOfType = emailTemplateRepository.findByUserAndTypeOrderByCreatedAtDesc(user, type);
        List<EmailTemplate> defaultTemplates = allTemplatesOfType.stream()
                .filter(EmailTemplate::getIsDefault)
                .collect(Collectors.toList());

        defaultTemplates.forEach(template -> template.setIsDefault(false));
        if (!defaultTemplates.isEmpty()) {
            emailTemplateRepository.saveAll(defaultTemplates);
        }
    }

    public String processTemplate(String templateContent, Customer customer, Business business, String reviewLink) {
        if (templateContent == null) return "";
        Map<String, String> variables = createCompliantVariableMapFromCustomer(customer);
        if (reviewLink != null) variables.put("reviewLink", reviewLink);
        return replaceVariables(templateContent, variables);
    }

    private String replaceVariables(String content, Map<String, String> variables) {
        if (content == null) return "";
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    // GOOGLE COMPLIANT: Variable map always includes ALL platform URLs
    private Map<String, String> createCompliantVariableMapFromCustomer(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", customer.getName() != null ? customer.getName() : "Valued Customer");
        variables.put("businessName", customer.getBusiness().getName() != null ? customer.getBusiness().getName() : "Our Business");
        variables.put("serviceType", customer.getServiceType() != null ? customer.getServiceType() : "service");
        variables.put("serviceDate", customer.getServiceDate() != null ? customer.getServiceDate().toString() : "recently");
        variables.put("businessPhone", customer.getBusiness().getPhone() != null ? customer.getBusiness().getPhone() : "");
        variables.put("businessWebsite", customer.getBusiness().getWebsite() != null ? customer.getBusiness().getWebsite() : "");

        Business business = customer.getBusiness();

        // ✅ GOOGLE COMPLIANT: Always generate ALL platform URLs
        variables.put("googleReviewUrl", generateGoogleReviewUrl(business));
        variables.put("facebookReviewUrl", generateFacebookReviewUrl(business));
        variables.put("yelpReviewUrl", generateYelpReviewUrl(business));
        variables.put("privateFeedbackUrl", frontendUrl + "/feedback/" + customer.getId());
        variables.put("unsubscribeUrl", frontendUrl + "/unsubscribe/" + customer.getId());

        return variables;
    }

    private String generateGoogleReviewUrl(Business business) {
        try {
            if (business.getGooglePlaceId() != null && !business.getGooglePlaceId().trim().isEmpty()) {
                return "https://search.google.com/local/writereview?placeid=" + business.getGooglePlaceId().trim();
            }
            if (business.getName() != null && business.getAddress() != null) {
                String searchQuery = (business.getName() + " " + business.getAddress()).trim();
                String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
                return "https://www.google.com/maps/search/" + encodedQuery;
            }
            if (business.getName() != null && !business.getName().trim().isEmpty()) {
                String encodedName = URLEncoder.encode(business.getName().trim() + " reviews", StandardCharsets.UTF_8);
                return "https://www.google.com/search?q=" + encodedName;
            }
            return "https://www.google.com/business/";
        } catch (Exception e) {
            return "https://www.google.com/business/";
        }
    }

    private String generateFacebookReviewUrl(Business business) {
        try {
            if (business.getFacebookPageUrl() != null && !business.getFacebookPageUrl().trim().isEmpty()) {
                String url = business.getFacebookPageUrl().trim();
                return url.endsWith("/") ? url + "reviews" : url + "/reviews";
            }
            return "https://facebook.com";
        } catch (Exception e) {
            return "https://facebook.com";
        }
    }

    private String generateYelpReviewUrl(Business business) {
        try {
            if (business.getYelpPageUrl() != null && !business.getYelpPageUrl().trim().isEmpty()) {
                return business.getYelpPageUrl().trim();
            }
            return "https://yelp.com";
        } catch (Exception e) {
            return "https://yelp.com";
        }
    }

    private String convertVariablesToString(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{{customerName}},{{businessName}},{{serviceType}},{{serviceDate}},{{businessPhone}},{{businessWebsite}},{{googleReviewUrl}},{{facebookReviewUrl}},{{yelpReviewUrl}},{{privateFeedbackUrl}},{{unsubscribeUrl}}";
        }
        return String.join(",", variables);
    }

    private List<String> convertStringToVariables(String variablesString) {
        if (variablesString == null || variablesString.trim().isEmpty()) {
            return Arrays.asList("{{customerName}}", "{{businessName}}", "{{serviceType}}", "{{serviceDate}}",
                    "{{businessPhone}}", "{{businessWebsite}}", "{{googleReviewUrl}}", "{{facebookReviewUrl}}",
                    "{{yelpReviewUrl}}", "{{privateFeedbackUrl}}", "{{unsubscribeUrl}}");
        }
        return Arrays.asList(variablesString.split(","));
    }

    private EmailTemplateDto convertToDto(EmailTemplate template) {
        return EmailTemplateDto.builder()
                .id(template.getId())
                .name(template.getName())
                .subject(template.getSubject())
                .body(template.getBody())
                .type(template.getType())
                .typeDisplayName(template.getType().getDisplayName())
                .isActive(template.getIsActive())
                .isDefault(template.getIsDefault())
                .isSystemTemplate(isSystemTemplate(template.getName()))
                .isHtml(template.getBody() != null &&
                        (template.getBody().contains("<html>") || template.getBody().contains("<!DOCTYPE")))
                .availableVariables(convertStringToVariables(template.getAvailableVariables()))
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }
}