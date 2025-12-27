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

    // Create clean, simple default templates
    @Transactional
    public void createGoogleCompliantTemplates(User user) {
        List<EmailTemplate> templates = Arrays.asList(
                // 1. Initial Review Request
                EmailTemplate.builder()
                        .name("Initial Review Request")
                        .subject("We'd love your feedback, {{customerName}}!")
                        .body(createSimpleInitialRequestTemplate())
                        .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                        .isActive(true)
                        .isDefault(true)
                        .simplifiedMode(true)
                        .buttonUrlType(EmailTemplate.ButtonUrlType.FEEDBACK_GATE)
                        .user(user)
                        .build(),

                // 2. 3-Day Follow-up
                EmailTemplate.builder()
                        .name("3-Day Follow-up")
                        .subject("Quick check-in: How was your {{serviceType}} experience?")
                        .body(createSimple3DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .simplifiedMode(true)
                        .buttonUrlType(EmailTemplate.ButtonUrlType.FEEDBACK_GATE)
                        .user(user)
                        .build(),

                // 3. 7-Day Follow-up
                EmailTemplate.builder()
                        .name("7-Day Follow-up")
                        .subject("Your opinion matters - {{businessName}}")
                        .body(createSimple7DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .simplifiedMode(true)
                        .buttonUrlType(EmailTemplate.ButtonUrlType.FEEDBACK_GATE)
                        .user(user)
                        .build(),

                // 4. 14-Day Final Follow-up
                EmailTemplate.builder()
                        .name("14-Day Final Follow-up")
                        .subject("Final request: Share your {{businessName}} experience")
                        .body(createSimple14DayFollowUpTemplate())
                        .type(EmailTemplate.TemplateType.FOLLOW_UP_14_DAY)
                        .isActive(true)
                        .isDefault(true)
                        .simplifiedMode(true)
                        .buttonUrlType(EmailTemplate.ButtonUrlType.FEEDBACK_GATE)
                        .user(user)
                        .build(),

                // 5. Thank You Template
                EmailTemplate.builder()
                        .name("Thank You for Your Review")
                        .subject("Thank you so much, {{customerName}}!")
                        .body(createSimpleThankYouTemplate())
                        .type(EmailTemplate.TemplateType.THANK_YOU)
                        .isActive(true)
                        .isDefault(true)
                        .simplifiedMode(true)
                        .buttonUrlType(EmailTemplate.ButtonUrlType.FEEDBACK_GATE)
                        .user(user)
                        .build()
        );

        emailTemplateRepository.saveAll(templates);
        log.info("✅ Created {} clean templates for user {}", templates.size(), user.getId());
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

    // Plain text that renderer will convert to HTML

    /**
     * Initial Review Request - Simple and friendly
     */
    private String createSimpleInitialRequestTemplate() {
        return """
Hi {{customerName}}!

Thank you for choosing {{businessName}} for your {{serviceType}}.

We hope you were satisfied with our service. We'd love to hear your honest feedback - it helps us improve and helps other customers make informed decisions.

Thanks!
    """.trim();
    }

    /**
     * 3-Day Follow-up - Gentle reminder
     */
    private String createSimple3DayFollowUpTemplate() {
        return """
Hi {{customerName}},

We wanted to follow up on the {{serviceType}} service we provided a few days ago.

We'd really appreciate it if you could take a moment to share your experience. Your feedback - whether positive or constructive - is valuable to us.

Thank you!
    """.trim();
    }

    /**
     * 7-Day Follow-up - Polite persistence
     */
    private String createSimple7DayFollowUpTemplate() {
        return """
Hi {{customerName}},

We hope you're still enjoying the results of your {{serviceType}} service!

We'd love to hear your thoughts about your experience with {{businessName}}. Your honest feedback helps us serve you and others better.

We appreciate your time!
    """.trim();
    }

    /**
     * 14-Day Final Follow-up - Last gentle request
     */
    private String createSimple14DayFollowUpTemplate() {
        return """
Hi {{customerName}},

This is our final request for feedback about the {{serviceType}} service we provided.

If you have a moment, we'd really value your honest review. Whether your experience was great or there's room for improvement, your feedback matters to us.

Thank you for considering!
    """.trim();
    }

    /**
     * Thank You - Gratitude for review
     */
    private String createSimpleThankYouTemplate() {
        return """
Thank you so much, {{customerName}}!

We received your review and we're truly grateful. Your honest feedback means the world to us and helps other customers make informed decisions.

We appreciate you taking the time to share your experience with {{businessName}}.

Thanks again!
    """.trim();
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

    // Create sample variables with all platform URLs
    private Map<String, String> createCompliantSampleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", "John Smith");
        variables.put("businessName", "ABC Home Services");
        variables.put("serviceType", "Kitchen Sink Repair");
        variables.put("serviceDate", "January 15, 2025");
        variables.put("businessPhone", "(555) 123-4567");
        variables.put("businessWebsite", "www.abchomeservices.com");

        // All platform URLs always available
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