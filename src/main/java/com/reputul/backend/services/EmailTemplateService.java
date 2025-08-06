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

    // FIXED: Main method to safely update user templates
    @Transactional
    public void safeUpdateUserTemplates(User user) {
        log.info("🔄 Safe template update for user: {}", user.getEmail());

        try {
            // Step 1: Get system template names to identify what to replace
            List<String> systemTemplateNames = Arrays.asList(
                    "Review Request Email",
                    "Multi-Platform Review Request",
                    "3-Day Follow-up",
                    "3-Day Follow-up (Multi-Platform)",
                    "7-Day Follow-up",
                    "7-Day Follow-up (Multi-Platform)",
                    "14-Day Follow-up",
                    "Thank You for Your Review"
            );

            // Step 2: Find old system templates to delete
            List<EmailTemplate> oldSystemTemplates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                    .stream()
                    .filter(template -> systemTemplateNames.contains(template.getName()))
                    .collect(Collectors.toList());

            log.info("Found {} old system templates to replace", oldSystemTemplates.size());

            // Step 3: Delete old system templates in separate transactions
            for (EmailTemplate oldTemplate : oldSystemTemplates) {
                deleteTemplateInSeparateTransaction(oldTemplate.getId());
            }

            // Step 4: Create new improved templates
            createImprovedDefaultTemplates(user);

            log.info("✅ Successfully updated templates for user: {}", user.getEmail());

        } catch (Exception e) {
            log.error("❌ Error updating templates for user {}: {}", user.getEmail(), e.getMessage());
            throw new RuntimeException("Failed to update templates for user: " + user.getEmail(), e);
        }
    }

    // FIXED: Delete template in separate transaction to avoid rollback issues
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteTemplateInSeparateTransaction(Long templateId) {
        try {
            emailTemplateRepository.findById(templateId).ifPresent(template -> {
                log.info("Deleting template: {} (ID: {})", template.getName(), templateId);
                emailTemplateRepository.delete(template);
            });
        } catch (DataIntegrityViolationException e) {
            log.warn("Cannot delete template ID {} - it's referenced by review requests", templateId);
            // Don't throw exception, just log and continue
        } catch (Exception e) {
            log.error("Error deleting template ID {}: {}", templateId, e.getMessage());
            // Don't throw exception, just log and continue
        }
    }

    // Standard template methods
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
        // Fix: Handle Optional<EmailTemplate> properly
        Optional<EmailTemplate> defaultTemplateOpt = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type);

        if (defaultTemplateOpt.isPresent()) {
            return convertToDto(defaultTemplateOpt.get());
        }

        // No default found, create system templates if needed
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
        boolean hasSystemHtmlTemplates = hasSystemDefaultTemplates(user);

        if (!hasSystemHtmlTemplates) {
            log.info("Creating system default HTML templates for user {} (no system HTML templates found)", user.getId());
            createImprovedDefaultTemplates(user);
        } else {
            log.info("User {} already has system HTML templates, skipping creation", user.getId());
        }
    }

    // BACKWARD COMPATIBILITY: Method for ReviewRequestService
    @Transactional
    public void forceCreateDefaultTemplatesForUser(User user) {
        log.info("Force updating templates for user {} (called from ReviewRequestService)", user.getEmail());
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

        Map<String, String> variables = createVariableMapFromCustomer(customer);
        return replaceVariables(template.getBody(), variables);
    }

    @Transactional(readOnly = true)
    public String renderTemplateSubject(User user, Long templateId, Customer customer) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Map<String, String> variables = createVariableMapFromCustomer(customer);
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

            Map<String, String> variables = createVariableMapFromCustomer(customer);
            return replaceVariables(template.getBody(), variables);

        } catch (Exception e) {
            // Fallback to a simple email if template fails
            return createFallbackEmail(customer);
        }
    }

    // IMPROVED: Create modern, professional templates
    @Transactional
    public void createImprovedDefaultTemplates(User user) {
        List<EmailTemplate> templates = Arrays.asList(
                // 1. Modern Initial Request Template
                EmailTemplate.builder()
                        .name("Review Request Email")
                        .subject("We'd love your feedback, {{customerName}}!")
                        .body(createModernInitialRequestTemplate())
                        .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 2. Multi-Platform Request Template
                EmailTemplate.builder()
                        .name("Multi-Platform Review Request")
                        .subject("Share your experience everywhere - {{businessName}}")
                        .body(createModernInitialRequestTemplate()) // Using same template for now
                        .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                        .isActive(true)
                        .isDefault(false)
                        .user(user)
                        .build(),

                // 3. Modern 3-Day Follow-up
                EmailTemplate.builder()
                        .name("3-Day Follow-up")
                        .subject("Quick check-in: How was your {{serviceType}} experience?")
                        .body(createModern3DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 4. 3-Day Multi-Platform Follow-up
                EmailTemplate.builder()
                        .name("3-Day Follow-up (Multi-Platform)")
                        .subject("Quick review options - wherever works best for you")
                        .body(createModern3DayFollowUpTemplate()) // Using same template
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                        .isActive(true)
                        .isDefault(false)
                        .user(user)
                        .build(),

                // 5. Modern 7-Day Follow-up
                EmailTemplate.builder()
                        .name("7-Day Follow-up")
                        .subject("Your opinion matters - {{businessName}}")
                        .body(createModern7DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 6. 7-Day Multi-Platform Follow-up
                EmailTemplate.builder()
                        .name("7-Day Follow-up (Multi-Platform)")
                        .subject("Help us grow with reviews on multiple platforms!")
                        .body(createModern7DayFollowUpTemplate()) // Using same template
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                        .isActive(true)
                        .isDefault(false)
                        .user(user)
                        .build(),

                // 7. Modern 14-Day Follow-up
                EmailTemplate.builder()
                        .name("14-Day Follow-up")
                        .subject("Final request: Share your {{businessName}} experience")
                        .body(createModern14DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_14_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build(),

                // 8. Modern Thank You Template
                EmailTemplate.builder()
                        .name("Thank You for Your Review")
                        .subject("Thank you so much, {{customerName}}!")
                        .body(createModernThankYouTemplate())
                        .type(EmailTemplate.TemplateType.THANK_YOU)
                        .isActive(true)
                        .isDefault(true)
                        .user(user)
                        .build()
        );

        emailTemplateRepository.saveAll(templates);
        log.info("✅ Created {} improved modern templates for user {}", templates.size(), user.getId());
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

        return stats;
    }

    // Template creation methods - EMAIL CLIENT COMPATIBLE
    private String createModernInitialRequestTemplate() {
        return """
        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
        <html xmlns="http://www.w3.org/1999/xhtml">
        <head>
            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Share Your Experience</title>
            <!--[if mso]>
            <style type="text/css">
            table, td {border-collapse: collapse; mso-table-lspace: 0pt; mso-table-rspace: 0pt;}
            </style>
            <![endif]-->
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
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">How was your experience?</p>
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
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">We hope you were completely satisfied with our service. Your feedback helps us improve and assists other customers in making informed decisions.</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- Review Buttons Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f8f9fa; border-radius: 8px; border: 1px solid #e9ecef;">
                                        <tr>
                                            <td style="padding: 30px 20px; text-align: center;">
                                                <h2 style="margin: 0 0 10px 0; color: #333333; font-size: 20px; font-weight: bold;">Share Your Experience</h2>
                                                <p style="margin: 0 0 25px 0; color: #666666; font-size: 14px;">Choose your preferred platform:</p>
                                                
                                                <!-- Google Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 15px auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{googleReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ffffff; border: 2px solid #4285f4; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #4285f4; font-weight: bold; font-size: 16px;">⭐ Review on Google</span>
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
                                                                            <span style="color: #1877f2; font-weight: bold; font-size: 16px;">👍 Review on Facebook</span>
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
                                                                            <span style="color: #d32323; font-weight: bold; font-size: 16px;">⭐ Review on Yelp</span>
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
                                                            <a href="{{privateReviewUrl}}" style="text-decoration: none;">
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
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #666666; font-size: 13px; text-align: center; font-style: italic;">
                                        Your feedback is incredibly valuable to us and helps us serve you better.
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

    private String createModern3DayFollowUpTemplate() {
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
                        <table cellpadding="0" cellspacing="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                            
                            <!-- Header -->
                            <tr>
                                <td style="background-color: #ff9800; color: #ffffff; padding: 40px 30px; text-align: center; border-radius: 8px 8px 0 0;">
                                    <h1 style="margin: 0; font-size: 28px; font-weight: bold; color: #ffffff;">{{businessName}}</h1>
                                    <p style="margin: 10px 0 0 0; font-size: 16px; color: #ffffff;">Just checking in with you</p>
                                </td>
                            </tr>
                            
                            <!-- Main Content -->
                            <tr>
                                <td style="padding: 40px 30px;">
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%">
                                        <tr>
                                            <td style="text-align: center; padding-bottom: 20px;">
                                                <span style="font-size: 40px;">🚀</span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td>
                                                <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                                <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been a few days since we completed your {{serviceType}} service on {{serviceDate}}.</p>
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">We wanted to quickly follow up and see how everything is going. If you have a moment, we'd really appreciate your feedback!</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- Review Buttons Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #fff3c4; border-radius: 8px; border: 2px solid #ffcc02;">
                                        <tr>
                                            <td style="padding: 30px 20px; text-align: center;">
                                                <h2 style="margin: 0 0 10px 0; color: #e65100; font-size: 20px; font-weight: bold;">How Did We Do? ⭐</h2>
                                                <p style="margin: 0 0 25px 0; color: #f57f17; font-size: 14px;">Just takes 30 seconds:</p>
                                                
                                                <!-- Google Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto 15px auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{googleReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ffffff; border: 2px solid #ffcc02; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #333333; font-weight: bold; font-size: 16px;">⭐ Google Review</span>
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
                                                            <a href="{{privateReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #ff9800; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #ffffff; font-weight: bold; font-size: 16px;">💬 Quick Feedback</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #f57f17; font-size: 13px; text-align: center;">
                                        We really appreciate you taking the time to share your experience!
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #fffbf0; padding: 25px 30px; border-top: 1px solid #fed7aa; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0 0 5px 0; font-weight: bold; color: #333333; font-size: 16px;">{{businessName}}</p>
                                    <p style="margin: 0 0 5px 0; font-size: 14px; color: #666666;">{{businessPhone}}</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">{{businessWebsite}}</p>
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

    private String createModern7DayFollowUpTemplate() {
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
                                <td style="padding: 40px 30px;">
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%">
                                        <tr>
                                            <td style="text-align: center; padding-bottom: 20px;">
                                                <span style="font-size: 40px;">💭</span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td>
                                                <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                                <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been a week since we completed your {{serviceType}} service on {{serviceDate}}.</p>
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">We hope everything is still working perfectly! If you have a moment, we'd be incredibly grateful for your honest feedback about your experience with us.</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- Review Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #ffebee; border-radius: 8px; border: 2px solid #ffcdd2;">
                                        <tr>
                                            <td style="padding: 30px 20px; text-align: center;">
                                                <h2 style="margin: 0 0 10px 0; color: #c62828; font-size: 20px; font-weight: bold;">Your Opinion Matters! 💭</h2>
                                                <p style="margin: 0 0 25px 0; color: #d32f2f; font-size: 14px;">Help future customers by sharing your experience:</p>
                                                
                                                <!-- Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{googleReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #d32f2f; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #ffffff; font-weight: bold; font-size: 16px;">⭐ Share Your Experience</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #d32f2f; font-size: 13px; text-align: center;">
                                        Your feedback helps us improve and helps other customers make informed decisions.
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #ffebee; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0 0 5px 0; font-weight: bold; color: #333333; font-size: 16px;">{{businessName}}</p>
                                    <p style="margin: 0 0 5px 0; font-size: 14px; color: #666666;">{{businessPhone}}</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">{{businessWebsite}}</p>
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

    private String createModern14DayFollowUpTemplate() {
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
                                <td style="padding: 40px 30px;">
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%">
                                        <tr>
                                            <td style="text-align: center; padding-bottom: 20px;">
                                                <span style="font-size: 40px;">🙏</span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td>
                                                <p style="margin: 0 0 15px 0; font-size: 16px; color: #333333;">Hi {{customerName}},</p>
                                                <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">It's been two weeks since we completed your {{serviceType}} service on {{serviceDate}}.</p>
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666;">This will be our final follow-up. If you were happy with our service and have a spare minute, we'd be tremendously grateful for a quick review. It really means the world to our small business!</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- Review Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f3e5f5; border-radius: 8px; border: 2px solid #ce93d8;">
                                        <tr>
                                            <td style="padding: 30px 20px; text-align: center;">
                                                <h2 style="margin: 0 0 10px 0; color: #4a148c; font-size: 20px; font-weight: bold;">Last Chance to Share! 🙏</h2>
                                                <p style="margin: 0 0 25px 0; color: #7b1fa2; font-size: 14px;">One quick review would mean everything to us:</p>
                                                
                                                <!-- Review Button -->
                                                <table cellpadding="0" cellspacing="0" border="0" style="margin: 0 auto;">
                                                    <tr>
                                                        <td>
                                                            <a href="{{googleReviewUrl}}" style="text-decoration: none;">
                                                                <table cellpadding="0" cellspacing="0" border="0" style="background-color: #7b1fa2; border-radius: 6px;">
                                                                    <tr>
                                                                        <td style="padding: 12px 30px; text-align: center;">
                                                                            <span style="color: #ffffff; font-weight: bold; font-size: 16px;">⭐ Final Review Request</span>
                                                                        </td>
                                                                    </tr>
                                                                </table>
                                                            </a>
                                                        </td>
                                                    </tr>
                                                </table>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 25px 0 0 0; color: #7b1fa2; font-size: 13px; text-align: center;">
                                        Thank you for being a valued customer. We hope to serve you again!
                                    </p>
                                </td>
                            </tr>
                            
                            <!-- Footer -->
                            <tr>
                                <td style="background-color: #f8f9fa; padding: 25px 30px; text-align: center; border-radius: 0 0 8px 8px;">
                                    <p style="margin: 0 0 5px 0; font-weight: bold; color: #333333; font-size: 16px;">{{businessName}}</p>
                                    <p style="margin: 0 0 5px 0; font-size: 14px; color: #666666;">{{businessPhone}}</p>
                                    <p style="margin: 0 0 15px 0; font-size: 14px; color: #666666;">{{businessWebsite}}</p>
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

    private String createModernThankYouTemplate() {
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
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%">
                                        <tr>
                                            <td style="text-align: center; padding-bottom: 30px;">
                                                <span style="font-size: 60px;">🎉</span>
                                            </td>
                                        </tr>
                                        <tr>
                                            <td>
                                                <h2 style="margin: 0 0 20px 0; font-size: 28px; color: #1b5e20; font-weight: bold; text-align: center;">Thank You, {{customerName}}!</h2>
                                                
                                                <p style="margin: 0 0 15px 0; font-size: 18px; color: #333333; text-align: center;">We received your review and we're absolutely thrilled!</p>
                                                <p style="margin: 0 0 30px 0; font-size: 14px; color: #666666; text-align: center;">Your feedback about our {{serviceType}} service means the world to us and helps other customers make informed decisions.</p>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <!-- Thank You Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #e8f5e8; border-radius: 8px; border: 2px solid #a5d6a7;">
                                        <tr>
                                            <td style="padding: 25px 20px; text-align: center;">
                                                <h3 style="margin: 0 0 20px 0; color: #2e7d32; font-size: 18px; font-weight: bold;">Why Your Review Matters</h3>
                                                <table cellpadding="0" cellspacing="0" border="0" width="100%" style="max-width: 400px; margin: 0 auto;">
                                                    <tr>
                                                        <td style="padding: 5px 0; color: #1b5e20; font-size: 14px;">
                                                            <span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Helps other customers find us
                                                        </td>
                                                    </tr>
                                                    <tr>
                                                        <td style="padding: 5px 0; color: #1b5e20; font-size: 14px;">
                                                            <span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Supports our small business
                                                        </td>
                                                    </tr>
                                                    <tr>
                                                        <td style="padding: 5px 0; color: #1b5e20; font-size: 14px;">
                                                            <span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Motivates our entire team
                                                        </td>
                                                    </tr>
                                                    <tr>
                                                        <td style="padding: 5px 0; color: #1b5e20; font-size: 14px;">
                                                            <span style="color: #2e7d32; font-weight: bold; margin-right: 10px;">✓</span>Helps us improve our service
                                                        </td>
                                                    </tr>
                                                </table>
                                            </td>
                                        </tr>
                                    </table>
                                    
                                    <p style="margin: 30px 0 20px 0; color: #2e7d32; font-size: 16px; font-weight: bold; text-align: center;">
                                        We appreciate you taking the time to share your experience!
                                    </p>
                                    
                                    <!-- Future Service Section -->
                                    <table cellpadding="0" cellspacing="0" border="0" width="100%" style="background-color: #f8f9fa; border-radius: 8px; border: 1px solid #e9ecef;">
                                        <tr>
                                            <td style="padding: 20px; text-align: center;">
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

    // Helper methods for preview and rendering - EMAIL CLIENT COMPATIBLE
    // REPLACE the existing generatePreview method with this:
    private EmailTemplatePreviewDto generatePreview(EmailTemplate template, Map<String, String> variableValues) {
        Map<String, String> variables = variableValues != null ? variableValues : createSampleVariables();

        String renderedSubject = replaceVariables(template.getSubject(), variables);
        String renderedBody = replaceVariables(template.getBody(), variables);

        // ✅ FIXED: Don't strip styling for preview - show full styled version
        // Only use makeEmailClientCompatible when actually sending emails, not for previews

        return EmailTemplatePreviewDto.builder()
                .subject(template.getSubject())
                .body(template.getBody())
                .variableValues(variables)
                .renderedSubject(renderedSubject)
                .renderedBody(renderedBody) // Use the full styled version without stripping
                .build();
    }

    // Make HTML more compatible with how email clients actually render it
    private String makeEmailClientCompatible(String htmlContent) {
        if (htmlContent == null) return "";

        // Email clients often strip out many modern CSS features
        // This method simulates what most email clients actually display
        String compatible = htmlContent;

        // Remove any CSS that email clients typically don't support
        compatible = compatible.replaceAll("(?i)linear-gradient\\([^)]+\\)", "#4a90e2"); // Replace gradients with solid colors
        compatible = compatible.replaceAll("(?i)box-shadow:[^;]+;", ""); // Remove box shadows
        compatible = compatible.replaceAll("(?i)border-radius:\\s*[0-9]+px", "border-radius: 8px"); // Limit border radius
        compatible = compatible.replaceAll("(?i)transition:[^;]+;", ""); // Remove transitions
        compatible = compatible.replaceAll("(?i)transform:[^;]+;", ""); // Remove transforms
        compatible = compatible.replaceAll("(?i)opacity:\\s*0\\.[0-9]+", "opacity: 1"); // Fix opacity issues

        // Ensure tables are used instead of divs for layout (email client requirement)
        // The templates are already table-based, so this is mainly for validation

        return compatible;
    }

    // REPLACE the existing createSampleVariables method with this:
    private Map<String, String> createSampleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", "John Smith");
        variables.put("businessName", "ABC Home Services");
        variables.put("serviceType", "Kitchen Sink Repair");
        variables.put("serviceDate", "January 15, 2025");
        variables.put("businessPhone", "(555) 123-4567");
        variables.put("businessWebsite", "www.abchomeservices.com");

        // ✅ IMPROVED: Add proper URLs for all review platforms
        variables.put("googleReviewUrl", "https://search.google.com/local/writereview?placeid=sample_place_id");
        variables.put("facebookReviewUrl", "https://facebook.com/abchomeservices/reviews");
        variables.put("yelpReviewUrl", "https://yelp.com/writeareview/biz/abc-home-services");
        variables.put("privateReviewUrl", "https://reputul.com/feedback/sample123");
        variables.put("unsubscribeUrl", "https://reputul.com/unsubscribe/sample123");

        // Additional variables that templates might use
        variables.put("reviewLink", "https://reputul.com/review/sample123");
        variables.put("supportEmail", "support@abchomeservices.com");
        variables.put("businessAddress", "123 Main St, Springfield, IL 62701");

        return variables;
    }

    private String createFallbackEmail(Customer customer) {
        Business business = customer.getBusiness();
        String googleUrl = generateGoogleReviewUrl(business);

        return String.format("""
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px;">
                <h2>%s</h2>
                <p>Hi %s,</p>
                <p>Thank you for choosing %s for your %s service on %s.</p>
                <p>We would appreciate your feedback:</p>
                <div style="text-align: center; margin: 20px 0;">
                    <a href="%s" style="background-color: #ffffff; color: #374151; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px; border: 2px solid #e5e7eb;">
                        <span style="color: #4285F4; font-weight: bold; margin-right: 8px;">G</span>Review on Google
                    </a>
                </div>
                <p>Best regards,<br>%s Team</p>
            </body>
            </html>
            """,
                business.getName() != null ? business.getName() : "Thank You",
                customer.getName() != null ? customer.getName() : "Valued Customer",
                business.getName() != null ? business.getName() : "Our Business",
                customer.getServiceType() != null ? customer.getServiceType() : "recent",
                customer.getServiceDate() != null ? customer.getServiceDate().toString() : "recently",
                googleUrl,
                business.getName() != null ? business.getName() : "Our Business"
        );
    }

    // Helper methods
    private boolean hasSystemDefaultTemplates(User user) {
        List<String> systemTemplateNames = Arrays.asList(
                "Review Request Email", "Multi-Platform Review Request", "3-Day Follow-up",
                "3-Day Follow-up (Multi-Platform)", "7-Day Follow-up", "7-Day Follow-up (Multi-Platform)",
                "14-Day Follow-up", "Thank You for Your Review"
        );

        return emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .anyMatch(template ->
                        systemTemplateNames.contains(template.getName()) &&
                                template.getBody() != null &&
                                (template.getBody().contains("<html>") || template.getBody().contains("<!DOCTYPE"))
                );
    }

    private boolean isSystemTemplate(String templateName) {
        List<String> systemTemplateNames = Arrays.asList(
                "Review Request Email", "Multi-Platform Review Request", "3-Day Follow-up",
                "3-Day Follow-up (Multi-Platform)", "7-Day Follow-up", "7-Day Follow-up (Multi-Platform)",
                "14-Day Follow-up", "Thank You for Your Review"
        );
        return systemTemplateNames.contains(templateName);
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
        Map<String, String> variables = createVariableMapFromCustomer(customer);
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

    private Map<String, String> createVariableMapFromCustomer(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", customer.getName() != null ? customer.getName() : "Valued Customer");
        variables.put("businessName", customer.getBusiness().getName() != null ? customer.getBusiness().getName() : "Our Business");
        variables.put("serviceType", customer.getServiceType() != null ? customer.getServiceType() : "service");
        variables.put("serviceDate", customer.getServiceDate() != null ? customer.getServiceDate().toString() : "recently");
        variables.put("businessPhone", customer.getBusiness().getPhone() != null ? customer.getBusiness().getPhone() : "");
        variables.put("businessWebsite", customer.getBusiness().getWebsite() != null ? customer.getBusiness().getWebsite() : "");

        Business business = customer.getBusiness();
        String googleReviewUrl = generateGoogleReviewUrl(business);

        variables.put("googleReviewUrl", googleReviewUrl);
        variables.put("facebookReviewUrl", business.getFacebookPageUrl() != null ?
                business.getFacebookPageUrl() + "/reviews" : "https://facebook.com");
        variables.put("yelpReviewUrl", business.getYelpPageUrl() != null ?
                business.getYelpPageUrl() : "https://yelp.com");
        variables.put("privateReviewUrl", "http://localhost:3000/feedback/" + customer.getId());
        variables.put("unsubscribeUrl", "http://localhost:3000/unsubscribe/" + customer.getId());

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

    private String convertVariablesToString(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{{customerName}},{{businessName}},{{serviceType}},{{serviceDate}},{{businessPhone}},{{businessWebsite}},{{googleReviewUrl}},{{facebookReviewUrl}},{{privateReviewUrl}},{{unsubscribeUrl}}";
        }
        return String.join(",", variables);
    }

    private List<String> convertStringToVariables(String variablesString) {
        if (variablesString == null || variablesString.trim().isEmpty()) {
            return Arrays.asList("{{customerName}}", "{{businessName}}", "{{serviceType}}", "{{serviceDate}}",
                    "{{businessPhone}}", "{{businessWebsite}}", "{{googleReviewUrl}}", "{{facebookReviewUrl}}",
                    "{{privateReviewUrl}}", "{{unsubscribeUrl}}");
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