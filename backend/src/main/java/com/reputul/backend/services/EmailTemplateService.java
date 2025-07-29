package com.reputul.backend.services;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class EmailTemplateService {

    private final EmailTemplateRepository emailTemplateRepository;

    public List<EmailTemplateDto> getAllTemplatesByUser(User user) {
        List<EmailTemplate> templates = emailTemplateRepository.findByUserOrderByCreatedAtDesc(user);
        return templates.stream()
                .map(this::convertToDto)
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

    public EmailTemplateDto getDefaultTemplate(User user, EmailTemplate.TemplateType type) {
        EmailTemplate template = emailTemplateRepository.findByUserAndTypeAndIsDefaultTrue(user, type)
                .orElseThrow(() -> new RuntimeException("No default template found for type: " + type));
        return convertToDto(template);
    }

    public EmailTemplateDto createTemplate(User user, CreateEmailTemplateRequest request) {
        // If this is set as default, unset other defaults of the same type
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
        return convertToDto(savedTemplate);
    }

    public EmailTemplateDto updateTemplate(User user, Long templateId, UpdateEmailTemplateRequest request) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));

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
        return convertToDto(savedTemplate);
    }

    public void deleteTemplate(User user, Long templateId) {
        EmailTemplate template = emailTemplateRepository.findByIdAndUser(templateId, user)
                .orElseThrow(() -> new RuntimeException("Template not found"));
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

    public void createDefaultTemplatesForUser(User user) {
        if (!emailTemplateRepository.existsByUser(user)) {
            createDefaultTemplates(user);
        }
    }

    private void createDefaultTemplates(User user) {
        // Initial Request Template
        EmailTemplate initialTemplate = EmailTemplate.builder()
                .name("Initial Review Request")
                .subject("We'd love your feedback, {{customerName}}!")
                .body("""
                    Hi {{customerName}},
                    
                    Thank you for choosing {{businessName}} for your recent {{serviceType}} on {{serviceDate}}.
                    
                    We hope you were completely satisfied with our service. Your feedback is incredibly important to us and helps other customers make informed decisions.
                    
                    Would you mind taking a moment to share your experience by leaving us a review?
                    
                    [Review Button/Link will be inserted here]
                    
                    Thank you for your time and for choosing {{businessName}}!
                    
                    Best regards,
                    The {{businessName}} Team
                    {{businessPhone}}
                    {{businessWebsite}}
                    """)
                .type(EmailTemplate.TemplateType.INITIAL_REQUEST)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // 3-Day Follow-up Template
        EmailTemplate followUp3Day = EmailTemplate.builder()
                .name("3-Day Follow-up")
                .subject("Quick reminder: Share your experience with {{businessName}}")
                .body("""
                    Hi {{customerName}},
                    
                    I hope you're doing well! We wanted to follow up regarding your recent {{serviceType}} with {{businessName}}.
                    
                    We'd still love to hear about your experience. Your review helps us improve our service and assists other customers in their decision-making.
                    
                    [Review Button/Link will be inserted here]
                    
                    If you've already left a review, thank you so much! If not, it would only take a minute and would mean the world to us.
                    
                    Best regards,
                    {{businessName}}
                    """)
                .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // 7-Day Follow-up Template
        EmailTemplate followUp7Day = EmailTemplate.builder()
                .name("7-Day Follow-up")
                .subject("Your feedback matters to {{businessName}}")
                .body("""
                    Hello {{customerName}},
                    
                    We hope you're still enjoying the results of your {{serviceType}} from {{businessName}}.
                    
                    We understand you're busy, but we'd be grateful if you could take just a moment to share your experience with us.
                    
                    [Review Button/Link will be inserted here]
                    
                    Your honest feedback helps us serve you and our community better.
                    
                    Thank you for your consideration!
                    
                    {{businessName}}
                    {{businessPhone}}
                    """)
                .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // Thank You Template
        EmailTemplate thankYou = EmailTemplate.builder()
                .name("Thank You for Review")
                .subject("Thank you for your review, {{customerName}}!")
                .body("""
                    Dear {{customerName}},
                    
                    Thank you so much for taking the time to leave us a review! Your feedback is invaluable to us.
                    
                    We're thrilled to hear about your positive experience with {{businessName}}, and we look forward to serving you again in the future.
                    
                    If you ever need {{serviceType}} or any of our other services, please don't hesitate to reach out.
                    
                    Warm regards,
                    The {{businessName}} Team
                    {{businessPhone}}
                    {{businessWebsite}}
                    """)
                .type(EmailTemplate.TemplateType.THANK_YOU)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        emailTemplateRepository.saveAll(Arrays.asList(initialTemplate, followUp3Day, followUp7Day, thankYou));
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
        String result = content;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            result = result.replace(placeholder, entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    private Map<String, String> createVariableMapFromCustomer(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", customer.getName());
        variables.put("businessName", customer.getBusiness().getName());
        variables.put("serviceType", customer.getServiceType());
        variables.put("serviceDate", customer.getServiceDate().toString());
        variables.put("businessPhone", customer.getBusiness().getPhone() != null ? customer.getBusiness().getPhone() : "");
        variables.put("businessWebsite", customer.getBusiness().getWebsite() != null ? customer.getBusiness().getWebsite() : "");
        return variables;
    }

    private Map<String, String> createSampleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", "John Smith");
        variables.put("businessName", "ABC Home Services");
        variables.put("serviceType", "Kitchen Sink Repair");
        variables.put("serviceDate", "January 15, 2025");
        variables.put("businessPhone", "(555) 123-4567");
        variables.put("businessWebsite", "www.abchomeservices.com");
        return variables;
    }

    private String convertVariablesToString(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{{customerName}},{{businessName}},{{serviceType}},{{serviceDate}},{{businessPhone}},{{businessWebsite}}";
        }
        return String.join(",", variables);
    }

    private List<String> convertStringToVariables(String variablesString) {
        if (variablesString == null || variablesString.trim().isEmpty()) {
            return Arrays.asList("{{customerName}}", "{{businessName}}", "{{serviceType}}", "{{serviceDate}}", "{{businessPhone}}", "{{businessWebsite}}");
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
                .availableVariables(convertStringToVariables(template.getAvailableVariables()))
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }

    public String processTemplate(String template, Customer customer, Business business, String reviewLink) {
        if (template == null) {
            return "";
        }

        return template
                .replace("{{customerName}}", customer.getName() != null ? customer.getName() : "")
                .replace("{{businessName}}", business.getName() != null ? business.getName() : "")
                .replace("{{serviceType}}", customer.getServiceType() != null ? customer.getServiceType() : "")
                .replace("{{serviceDate}}", customer.getServiceDate() != null ? customer.getServiceDate().toString() : "")
                .replace("{{businessPhone}}", business.getPhone() != null ? business.getPhone() : "")
                .replace("{{businessWebsite}}", business.getWebsite() != null ? business.getWebsite() : "")
                .replace("{{reviewLink}}", reviewLink != null ? reviewLink : "");
    }
}