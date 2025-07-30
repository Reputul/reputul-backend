package com.reputul.backend.services;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;

    /**
     * List all templates with clear indication of system vs user-created
     */
    public List<EmailTemplateDto> getAllTemplatesByUser(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .map(template -> {
                    EmailTemplateDto dto = convertToDto(template);
                    // Add metadata about template origin
                    dto.setIsSystemTemplate(isSystemTemplate(template.getName()));
                    return dto;
                })
                .collect(Collectors.toList());
    }

    public List<EmailTemplateDto> getActiveTemplatesByUser(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserAndIsActiveTrueOrderByCreatedAtDesc(user);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public List<EmailTemplateDto> getTemplatesByType(User user, EmailTemplate.TemplateType type) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserAndTypeOrderByCreatedAtDesc(user, type);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public EmailTemplateDto getTemplateById(User user, Long templateId) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));
        return convertToDto(template);
    }

    /**
     * Smart default selection - prefer user templates, fallback to system
     */
    public EmailTemplateDto getDefaultTemplate(User user, EmailTemplate.TemplateType type) {
        // First try to get user's chosen default
        List<EmailTemplate> defaultTemplates = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type)
                .stream().collect(Collectors.toList());

        if (!defaultTemplates.isEmpty()) {
            // Prefer user-created templates over system templates
            EmailTemplate userTemplate = defaultTemplates.stream()
                    .filter(t -> !isSystemTemplate(t.getName()))
                    .findFirst()
                    .orElse(defaultTemplates.get(0)); // fallback to any default

            return convertToDto(userTemplate);
        }

        // No default found, create system templates if needed
        createDefaultTemplatesForUser(user);

        // Try again after creating system templates
        EmailTemplate template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type)
                .orElseThrow(() -> new RuntimeException("No default template found for type: " + type));

        return convertToDto(template);
    }

    /**
     * SAFE: Create template (never overwrites existing)
     */
    public EmailTemplateDto createTemplate(User user, CreateEmailTemplateRequest request) {
        // If user wants this as default, unset OTHER defaults of same type (but keep the templates)
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

    /**
     * SAFE: Update template (only if user owns it)
     */
    public EmailTemplateDto updateTemplate(User user, Long templateId, UpdateEmailTemplateRequest request) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found or access denied"));

        // If this is set as default, unset other defaults of the same type
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

    /**
     * SAFE: Delete template (only if user owns it, never auto-delete)
     */
    public void deleteTemplate(User user, Long templateId) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found or access denied"));

        log.info("User {} manually deleting template '{}' (ID: {})", user.getId(), template.getName(), templateId);
        emailTemplateRepository.delete(template);
    }

    public EmailTemplatePreviewDto previewTemplate(User user, Long templateId, Map<String, String> variableValues) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        return generatePreview(template, variableValues);
    }

    public EmailTemplatePreviewDto previewTemplateContent(String subject, String body, Map<String, String> variableValues) {
        EmailTemplate tempTemplate = EmailTemplate.builder()
                .subject(subject)
                .body(body)
                .build();

        return generatePreview(tempTemplate, variableValues);
    }

    public String renderTemplate(User user, Long templateId, Customer customer) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Map<String, String> variables = createVariableMapFromCustomer(customer);
        return replaceVariables(template.getBody(), variables);
    }

    public String renderTemplateSubject(User user, Long templateId, Customer customer) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

        Map<String, String> variables = createVariableMapFromCustomer(customer);
        return replaceVariables(template.getSubject(), variables);
    }

    public List<EmailTemplateDto> searchTemplates(User user, String searchTerm) {
        List<EmailTemplate> templates = emailTemplateRepository.searchTemplates(user, searchTerm);
        return templates.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get user-created templates only (excludes system templates)
     */
    public List<EmailTemplateDto> getUserCreatedTemplates(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .filter(template -> !isSystemTemplate(template.getName()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    /**
     * Get system templates only
     */
    public List<EmailTemplateDto> getSystemTemplates(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .filter(template -> isSystemTemplate(template.getName()))
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public void createDefaultTemplatesForUser(User user) {
        // Check if user has any system default HTML templates
        boolean hasSystemHtmlTemplates = hasSystemDefaultTemplates(user);

        if (!hasSystemHtmlTemplates) {
            log.info("Creating system default HTML templates for user {} (no system HTML templates found)", user.getId());
            createDefaultTemplates(user);
        } else {
            log.info("User {} already has system HTML templates, skipping creation", user.getId());
        }
    }

    /**
     * Force update ONLY system templates (preserves user-created templates)
     */
    public void forceCreateDefaultTemplatesForUser(User user) {
        log.info("Force updating SYSTEM default templates for user {} (preserving user-created templates)", user.getId());

        // Get system template names to identify which ones to replace
        List<String> systemTemplateNames = Arrays.asList(
                "Review Request Email",
                "Multi-Platform Review Request",
                "3-Day Follow-up",
                "3-Day Follow-up (Multi-Platform)",
                "7-Day Follow-up",
                "7-Day Follow-up (Multi-Platform)",
                "Thank You for Your Review"
        );

        // Find ONLY system templates (by name) that are currently default
        List<EmailTemplate> systemTemplatesOnly = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .filter(template -> systemTemplateNames.contains(template.getName()) && template.getIsDefault())
                .collect(Collectors.toList());

        log.info("Found {} system default templates to update (preserving {} user templates)",
                systemTemplatesOnly.size(),
                emailTemplateRepository.findByUserOrderByCreatedAtDesc(user).size() - systemTemplatesOnly.size());

        // Mark ONLY system templates as non-default (preserve user templates)
        systemTemplatesOnly.forEach(template -> {
            template.setIsDefault(false);
            log.info("Marking system template '{}' as non-default", template.getName());
        });
        emailTemplateRepository.saveAll(systemTemplatesOnly);

        // Create new system templates (won't affect user templates)
        createDefaultTemplates(user);
    }

    /**
     * Check if user has system-generated default templates (not user-created ones)
     */
    private boolean hasSystemDefaultTemplates(User user) {
        List<String> systemTemplateNames = Arrays.asList(
                "Review Request Email",
                "Multi-Platform Review Request",
                "3-Day Follow-up",
                "3-Day Follow-up (Multi-Platform)",
                "7-Day Follow-up",
                "7-Day Follow-up (Multi-Platform)",
                "Thank You for Your Review"
        );

        return emailTemplateRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .anyMatch(template ->
                        systemTemplateNames.contains(template.getName()) &&
                                template.getBody() != null &&
                                (template.getBody().contains("<html>") || template.getBody().contains("<!DOCTYPE"))
                );
    }

    /**
     * Check if template is a system-generated template (not user-created)
     */
    private boolean isSystemTemplate(String templateName) {
        List<String> systemTemplateNames = Arrays.asList(
                "Review Request Email",
                "Multi-Platform Review Request",
                "3-Day Follow-up",
                "3-Day Follow-up (Multi-Platform)",
                "7-Day Follow-up",
                "7-Day Follow-up (Multi-Platform)",
                "Thank You for Your Review"
        );
        return systemTemplateNames.contains(templateName);
    }

    // MAIN EMAIL PROCESSING METHOD - Use this for sending emails
    public String processTemplate(String templateContent, Customer customer, Business business, String reviewLink) {
        if (templateContent == null) {
            return "";
        }

        Map<String, String> variables = createVariableMapFromCustomer(customer);

        // Add legacy support for reviewLink if needed
        if (reviewLink != null) {
            variables.put("reviewLink", reviewLink);
        }

        return replaceVariables(templateContent, variables);
    }

    // ALTERNATIVE: Direct template processing with customer data
    public String processTemplateWithCustomer(Customer customer) {
        try {
            // FIXED: Use getUser() since you renamed owner -> user
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

    private void createDefaultTemplates(User user) {
        // Simple but effective email template with working buttons
        EmailTemplate initialTemplate = EmailTemplate.builder()
                .name("Review Request Email")
                .subject("How was your {{serviceType}} experience, {{customerName}}?")
                .body("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Share Your Experience</title>
                    </head>
                    <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #f4f4f4;">
                        <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                            
                            <!-- Header -->
                            <div style="background-color: #2563eb; color: white; padding: 30px 20px; text-align: center;">
                                <h1 style="margin: 0; font-size: 24px;">{{businessName}}</h1>
                                <p style="margin: 5px 0 0 0; opacity: 0.9;">We value your feedback</p>
                            </div>
                            
                            <!-- Main Content -->
                            <div style="padding: 30px 20px;">
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Hi {{customerName}},</p>
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you for choosing {{businessName}} for your {{serviceType}} on {{serviceDate}}.</p>
                                <p style="margin: 0 0 25px 0; font-size: 16px;">We hope you were completely satisfied with our service. Your honest feedback helps us improve and assists other customers in making informed decisions.</p>
                                
                                <!-- Review Buttons Section -->
                                <div style="background-color: #f8fafc; padding: 25px; border-radius: 8px; text-align: center; margin: 25px 0;">
                                    <h2 style="margin: 0 0 20px 0; color: #374151; font-size: 20px;">Share Your Experience</h2>
                                    <p style="margin: 0 0 20px 0; color: #6b7280; font-size: 14px;">Choose your preferred platform:</p>
                                    
                                    <!-- Google Review Button -->
                                    <div style="margin-bottom: 15px;">
                                        <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #16a34a; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                            🌟 Leave Google Review
                                        </a>
                                    </div>
                                    
                                    <!-- Facebook Review Button -->
                                    <div style="margin-bottom: 15px;">
                                        <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #1877f2; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                            📘 Facebook Review
                                        </a>
                                    </div>
                                    
                                    <!-- Private Feedback Button -->
                                    <div style="margin-bottom: 15px;">
                                        <a href="{{privateReviewUrl}}" style="display: inline-block; background-color: #6b7280; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                            💬 Private Feedback
                                        </a>
                                    </div>
                                </div>
                            </div>
                            
                            <!-- Footer -->
                            <div style="background-color: #f9fafb; padding: 20px; border-top: 1px solid #e5e7eb; text-align: center;">
                                <p style="margin: 0 0 5px 0; font-weight: bold; color: #374151;">{{businessName}}</p>
                                <p style="margin: 0 0 5px 0; font-size: 14px; color: #6b7280;">{{businessPhone}}</p>
                                <p style="margin: 0 0 15px 0; font-size: 14px; color: #6b7280;">{{businessWebsite}}</p>
                                <p style="margin: 0; font-size: 12px; color: #9ca3af;">
                                    <a href="{{unsubscribeUrl}}" style="color: #6b7280; text-decoration: none;">Unsubscribe</a>
                                </p>
                            </div>
                        </div>
                    </body>
                    </html>
                    """)
                .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // Save just the initial template for now - add others later if needed
        emailTemplateRepository.save(initialTemplate);
        log.info("Created default HTML template for user {}", user.getId());
    }

    private void unsetDefaultsForType(User user, EmailTemplate.TemplateType type) {
        List<EmailTemplate> defaultTemplates = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type)
                .stream().collect(Collectors.toList());

        defaultTemplates.forEach(template -> template.setIsDefault(false));
        emailTemplateRepository.saveAll(defaultTemplates);
    }

    private EmailTemplatePreviewDto generatePreview(EmailTemplate template, Map<String, String> variableValues) {
        Map<String, String> variables = variableValues != null ? variableValues : createSampleVariables();

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

    private String replaceVariables(String content, Map<String, String> variables) {
        if (content == null) {
            return "";
        }

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

        // SMART GOOGLE URL GENERATION WITH FALLBACKS
        Business business = customer.getBusiness();
        String googleReviewUrl = generateGoogleReviewUrl(business);

        variables.put("googleReviewUrl", googleReviewUrl);
        variables.put("facebookReviewUrl", business.getFacebookPageUrl() != null ?
                business.getFacebookPageUrl() + "/reviews" : "https://facebook.com");

        // FIXED: Match your existing React route with DEBUG LOGGING
        String privateReviewUrl = "http://localhost:3000/feedback/" + customer.getId();
        log.info("🔗 Generated private review URL: {} for customer ID: {}", privateReviewUrl, customer.getId());

        variables.put("privateReviewUrl", privateReviewUrl);
        variables.put("unsubscribeUrl", "http://localhost:3000/unsubscribe/" + customer.getId());

        return variables;
    }

    private String generateGoogleReviewUrl(Business business) {
        try {
            // Priority 1: Use Place ID if available (most direct)
            if (business.getGooglePlaceId() != null && !business.getGooglePlaceId().trim().isEmpty()) {
                return "https://search.google.com/local/writereview?placeid=" + business.getGooglePlaceId().trim();
            }

            // Priority 2: Use business name + address for Google Maps search
            if (business.getName() != null && business.getAddress() != null) {
                String searchQuery = (business.getName() + " " + business.getAddress()).trim();
                String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);
                return "https://www.google.com/maps/search/" + encodedQuery;
            }

            // Priority 3: Use just business name for Google search
            if (business.getName() != null && !business.getName().trim().isEmpty()) {
                String encodedName = URLEncoder.encode(business.getName().trim() + " reviews", StandardCharsets.UTF_8);
                return "https://www.google.com/search?q=" + encodedName;
            }

            // Priority 4: Ultimate fallback - Google My Business homepage
            return "https://www.google.com/business/";

        } catch (Exception e) {
            // If encoding fails, return safe fallback
            return "https://www.google.com/business/";
        }
    }

    private Map<String, String> createSampleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", "John Smith");
        variables.put("businessName", "ABC Home Services");
        variables.put("serviceType", "Kitchen Sink Repair");
        variables.put("serviceDate", "January 15, 2025");
        variables.put("businessPhone", "(555) 123-4567");
        variables.put("businessWebsite", "www.abchomeservices.com");
        variables.put("googleReviewUrl", "https://www.google.com/maps/search/ABC%20Home%20Services%20123%20Main%20St%20Chicago");
        variables.put("facebookReviewUrl", "https://facebook.com/abchomeservices/reviews");
        variables.put("privateReviewUrl", "https://reputul.com/feedback/sample123");
        variables.put("unsubscribeUrl", "https://reputul.com/unsubscribe/sample123");

        return variables;
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

    // FALLBACK: Simple email if templates fail
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
                    <a href="%s" style="background-color: #4CAF50; color: white; padding: 15px 25px; text-decoration: none; border-radius: 5px; display: inline-block; margin: 5px;">
                        ⭐ Leave Google Review
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
}