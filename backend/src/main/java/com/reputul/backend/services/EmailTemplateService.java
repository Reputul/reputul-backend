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
        // Initial Request Template - Updated Single-Column Multi-Platform Design
        EmailTemplate initialTemplate = EmailTemplate.builder()
                .name("Multi-Platform Review Request")
                .subject("How was your {{serviceType}} experience, {{customerName}}?")
                .body("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Share Your Experience</title>
                    </head>
                    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px; background-color: #ffffff;">
                            <div style="text-align: center; margin-bottom: 30px;">
                                <h1 style="color: #2563eb; font-size: 24px; margin: 0;">{{businessName}}</h1>
                                <p style="color: #6b7280; margin: 5px 0 0 0; font-size: 14px;">We value your feedback</p>
                            </div>
                            
                            <div style="background-color: #f8fafc; padding: 25px; border-radius: 12px; margin-bottom: 25px;">
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Hi {{customerName}},</p>
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you for choosing {{businessName}} for your {{serviceType}} on {{serviceDate}}.</p>
                                <p style="margin: 0; font-size: 16px;">We hope you were completely satisfied with our service. Your honest feedback helps us improve and assists other customers in making informed decisions.</p>
                            </div>
                            
                            <div style="text-align: center; margin: 30px 0;">
                                <h2 style="font-size: 20px; color: #374151; margin: 0 0 20px 0;">Share Your Experience</h2>
                                <p style="margin: 0 0 25px 0; color: #6b7280; font-size: 14px;">We'd love your feedback. You can leave a review on your preferred platform below:</p>
                                
                                <!-- Single Column Review Buttons -->
                                <div style="max-width: 300px; margin: 0 auto;">
                                    <!-- Google Review Button -->
                                    <a href="{{googleReviewUrl}}" style="display: block; background-color: #16a34a; color: white; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: 500; text-align: center; margin-bottom: 12px; font-size: 16px;">
                                        📍 Google Review
                                    </a>
                                    
                                    <!-- Facebook Review Button -->
                                    <a href="{{facebookReviewUrl}}" style="display: block; background-color: #2563eb; color: white; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: 500; text-align: center; margin-bottom: 12px; font-size: 16px;">
                                        👥 Facebook Review
                                    </a>
                                    
                                    <!-- Yelp Review Button (Commented out for future use)
                                    <a href="{{yelpReviewUrl}}" style="display: block; background-color: #dc2626; color: white; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: 500; text-align: center; margin-bottom: 12px; font-size: 16px;">
                                        ⭐ Yelp Review
                                    </a>
                                    -->
                                    
                                    <!-- Private Feedback Button -->
                                    <a href="{{privateReviewUrl}}" style="display: block; background-color: #4b5563; color: white; text-decoration: none; padding: 12px 24px; border-radius: 8px; font-weight: 500; text-align: center; margin-bottom: 12px; font-size: 16px;">
                                        💬 Private Feedback
                                    </a>
                                </div>
                                
                                <p style="font-size: 12px; color: #6b7280; margin-top: 16px;">
                                    Choose the platform that works best for you
                                </p>
                            </div>
                            
                            <div style="border-top: 1px solid #e5e7eb; padding-top: 20px; margin-top: 30px;">
                                <p style="margin: 0 0 10px 0; font-size: 14px; color: #374151;">Best regards,</p>
                                <p style="margin: 0 0 5px 0; font-weight: 600; font-size: 14px; color: #374151;">The {{businessName}} Team</p>
                                <p style="margin: 0; font-size: 13px; color: #6b7280;">{{businessPhone}} | {{businessWebsite}}</p>
                            </div>
                            
                            <div style="text-align: center; margin-top: 30px; padding-top: 20px; border-top: 1px solid #e5e7eb;">
                                <p style="margin: 0; font-size: 11px; color: #9ca3af;">
                                    You received this email because you recently used our services. 
                                    If you no longer wish to receive these emails, please <a href="{{unsubscribeUrl}}" style="color: #6b7280;">unsubscribe here</a>.
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

        // 3-Day Follow-up Template - Updated Single-Column Design
        EmailTemplate followUp3Day = EmailTemplate.builder()
                .name("3-Day Follow-up (Multi-Platform)")
                .subject("Quick follow-up from {{businessName}}")
                .body("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Follow-up</title>
                    </head>
                    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px; background-color: #ffffff;">
                            <div style="text-align: center; margin-bottom: 25px;">
                                <h1 style="color: #2563eb; font-size: 22px; margin: 0;">{{businessName}}</h1>
                            </div>
                            
                            <div style="background-color: #fef3c7; padding: 20px; border-radius: 10px; border-left: 4px solid #f59e0b; margin-bottom: 25px;">
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Hi {{customerName}},</p>
                                <p style="margin: 0 0 15px 0; font-size: 16px;">We hope you're still enjoying the results of your {{serviceType}}!</p>
                                <p style="margin: 0; font-size: 16px;">We'd love to hear about your experience if you have a moment to share.</p>
                            </div>
                            
                            <div style="text-align: center; margin: 25px 0;">
                                <h3 style="font-size: 18px; color: #374151; margin: 0 0 20px 0;">Share Your Experience</h3>
                                
                                <!-- Single Column Review Buttons -->
                                <div style="max-width: 250px; margin: 0 auto;">
                                    <!-- Google Review Button -->
                                    <a href="{{googleReviewUrl}}" style="display: block; background-color: #16a34a; color: white; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        📍 Google Review
                                    </a>
                                    
                                    <!-- Facebook Review Button -->
                                    <a href="{{facebookReviewUrl}}" style="display: block; background-color: #2563eb; color: white; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        👥 Facebook Review
                                    </a>
                                    
                                    <!-- Yelp Review Button (Commented out for future use)
                                    <a href="{{yelpReviewUrl}}" style="display: block; background-color: #dc2626; color: white; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        ⭐ Yelp Review
                                    </a>
                                    -->
                                    
                                    <!-- Private Feedback Button -->
                                    <a href="{{privateReviewUrl}}" style="display: block; background-color: #4b5563; color: white; text-decoration: none; padding: 10px 20px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        💬 Private Feedback
                                    </a>
                                </div>
                            </div>
                            
                            <div style="text-align: center; margin-top: 25px;">
                                <p style="margin: 0; font-size: 14px; color: #6b7280;">Thanks for being a valued customer!</p>
                                <p style="margin: 5px 0 0 0; font-weight: 600; font-size: 14px; color: #374151;">{{businessName}}</p>
                            </div>
                            
                            <div style="text-align: center; margin-top: 25px; padding-top: 15px; border-top: 1px solid #e5e7eb;">
                                <p style="margin: 0; font-size: 11px; color: #9ca3af;">
                                    <a href="{{unsubscribeUrl}}" style="color: #6b7280;">Unsubscribe</a> from review requests
                                </p>
                            </div>
                        </div>
                    </body>
                    </html>
                    """)
                .type(EmailTemplate.TemplateType.FOLLOW_UP_3_DAY)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // 7-Day Follow-up Template - Updated Single-Column Design
        EmailTemplate followUp7Day = EmailTemplate.builder()
                .name("7-Day Follow-up (Multi-Platform)")
                .subject("Your feedback would help {{businessName}}")
                .body("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Your Feedback Matters</title>
                    </head>
                    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px; background-color: #ffffff;">
                            <div style="text-align: center; margin-bottom: 25px;">
                                <h1 style="color: #2563eb; font-size: 22px; margin: 0;">{{businessName}}</h1>
                            </div>
                            
                            <div style="background-color: #f0f9ff; padding: 20px; border-radius: 10px; border-left: 4px solid #0ea5e9; margin-bottom: 25px;">
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Hello {{customerName}},</p>
                                <p style="margin: 0 0 15px 0; font-size: 16px;">We hope you're enjoying the benefits of your {{serviceType}} service.</p>
                                <p style="margin: 0; font-size: 16px;">Your honest feedback helps us serve you and our community better. Would you mind sharing your experience?</p>
                            </div>
                            
                            <div style="text-align: center; margin: 25px 0;">
                                <h3 style="font-size: 18px; color: #374151; margin: 0 0 15px 0;">Choose your preferred platform:</h3>
                                
                                <!-- Single Column Review Buttons -->
                                <div style="max-width: 250px; margin: 0 auto;">
                                    <!-- Google Review Button -->
                                    <a href="{{googleReviewUrl}}" style="display: block; background-color: #16a34a; color: white; text-decoration: none; padding: 10px 18px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        📍 Google Review
                                    </a>
                                    
                                    <!-- Facebook Review Button -->
                                    <a href="{{facebookReviewUrl}}" style="display: block; background-color: #2563eb; color: white; text-decoration: none; padding: 10px 18px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        👥 Facebook Review
                                    </a>
                                    
                                    <!-- Yelp Review Button (Commented out for future use)
                                    <a href="{{yelpReviewUrl}}" style="display: block; background-color: #dc2626; color: white; text-decoration: none; padding: 10px 18px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        ⭐ Yelp Review
                                    </a>
                                    -->
                                    
                                    <!-- Private Feedback Button -->
                                    <a href="{{privateReviewUrl}}" style="display: block; background-color: #4b5563; color: white; text-decoration: none; padding: 10px 18px; border-radius: 6px; font-weight: 500; text-align: center; margin-bottom: 10px; font-size: 14px;">
                                        💬 Private Feedback
                                    </a>
                                </div>
                            </div>
                            
                            <div style="text-align: center; margin-top: 25px;">
                                <p style="margin: 0 0 5px 0; font-size: 14px; color: #374151;">Thank you for your consideration!</p>
                                <p style="margin: 0; font-size: 13px; color: #6b7280;">{{businessPhone}}</p>
                            </div>
                            
                            <div style="text-align: center; margin-top: 25px; padding-top: 15px; border-top: 1px solid #e5e7eb;">
                                <p style="margin: 0; font-size: 11px; color: #9ca3af;">
                                    <a href="{{unsubscribeUrl}}" style="color: #6b7280;">Unsubscribe</a> from review requests
                                </p>
                            </div>
                        </div>
                    </body>
                    </html>
                    """)
                .type(EmailTemplate.TemplateType.FOLLOW_UP_7_DAY)
                .isActive(true)
                .isDefault(true)
                .user(user)
                .build();

        // Thank You Template
        EmailTemplate thankYou = EmailTemplate.builder()
                .name("Thank You for Your Review")
                .subject("Thank you for your review, {{customerName}}!")
                .body("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>Thank You</title>
                    </head>
                    <body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif; line-height: 1.6; color: #333;">
                        <div style="max-width: 600px; margin: 0 auto; padding: 20px; background-color: #ffffff;">
                            <div style="text-align: center; margin-bottom: 25px;">
                                <h1 style="color: #059669; font-size: 24px; margin: 0;">Thank You! 🙏</h1>
                                <p style="color: #6b7280; margin: 5px 0 0 0; font-size: 14px;">{{businessName}}</p>
                            </div>
                            
                            <div style="background-color: #f0fdf4; padding: 25px; border-radius: 12px; border-left: 4px solid #10b981; margin-bottom: 25px;">
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Dear {{customerName}},</p>
                                <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you so much for taking the time to share your experience! Your feedback means the world to us.</p>
                                <p style="margin: 0; font-size: 16px;">We're thrilled to hear about your positive experience with {{businessName}}.</p>
                            </div>
                            
                            <div style="text-align: center; background-color: #fafafa; padding: 20px; border-radius: 10px; margin: 25px 0;">
                                <p style="margin: 0 0 15px 0; font-size: 16px; color: #374151;">We look forward to serving you again!</p>
                                <p style="margin: 0; font-size: 14px; color: #6b7280;">If you need {{serviceType}} or any of our other services, please don't hesitate to reach out.</p>
                            </div>
                            
                            <div style="text-align: center; margin-top: 25px;">
                                <p style="margin: 0 0 10px 0; font-size: 14px; color: #374151;">Warm regards,</p>
                                <p style="margin: 0 0 5px 0; font-weight: 600; font-size: 14px; color: #374151;">The {{businessName}} Team</p>
                                <p style="margin: 0; font-size: 13px; color: #6b7280;">{{businessPhone}} | {{businessWebsite}}</p>
                            </div>
                        </div>
                    </body>
                    </html>
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

    // UPDATED: Smart Google URL generation with fallbacks
    private Map<String, String> createVariableMapFromCustomer(Customer customer) {
        Map<String, String> variables = new HashMap<>();
        variables.put("customerName", customer.getName());
        variables.put("businessName", customer.getBusiness().getName());
        variables.put("serviceType", customer.getServiceType());
        variables.put("serviceDate", customer.getServiceDate().toString());
        variables.put("businessPhone", customer.getBusiness().getPhone() != null ? customer.getBusiness().getPhone() : "");
        variables.put("businessWebsite", customer.getBusiness().getWebsite() != null ? customer.getBusiness().getWebsite() : "");

        // SMART GOOGLE URL GENERATION WITH FALLBACKS
        Business business = customer.getBusiness();
        String googleReviewUrl = generateGoogleReviewUrl(business);

        variables.put("googleReviewUrl", googleReviewUrl);
        variables.put("facebookReviewUrl", business.getFacebookPageUrl() != null ?
                business.getFacebookPageUrl() + "/reviews" : "#");
        // variables.put("yelpReviewUrl", business.getYelpPageUrl() != null ? business.getYelpPageUrl() : "#"); // Commented out for future use
        variables.put("privateReviewUrl", "https://reputul.com/feedback/" + customer.getId());
        variables.put("unsubscribeUrl", "https://reputul.com/unsubscribe/" + customer.getId());

        return variables;
    }

    // NEW METHOD: Smart Google URL generation with fallbacks
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

        // SMART SAMPLE GOOGLE URL (showing fallback behavior)
        variables.put("googleReviewUrl", "https://www.google.com/maps/search/ABC%20Home%20Services%20123%20Main%20St%20Chicago");
        variables.put("facebookReviewUrl", "https://facebook.com/abchomeservices/reviews");
        // variables.put("yelpReviewUrl", "https://yelp.com/biz/abc-home-services"); // Commented out for future use
        variables.put("privateReviewUrl", "https://reputul.com/feedback/sample123");
        variables.put("unsubscribeUrl", "https://reputul.com/unsubscribe/sample123");

        return variables;
    }

    private String convertVariablesToString(List<String> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{{customerName}},{{businessName}},{{serviceType}},{{serviceDate}},{{businessPhone}},{{businessWebsite}},{{googleReviewUrl}},{{facebookReviewUrl}},{{privateReviewUrl}},{{unsubscribeUrl}}";
            // Yelp removed: "{{yelpReviewUrl}}"
        }
        return String.join(",", variables);
    }

    private List<String> convertStringToVariables(String variablesString) {
        if (variablesString == null || variablesString.trim().isEmpty()) {
            return Arrays.asList("{{customerName}}", "{{businessName}}", "{{serviceType}}", "{{serviceDate}}",
                    "{{businessPhone}}", "{{businessWebsite}}", "{{googleReviewUrl}}", "{{facebookReviewUrl}}",
                    "{{privateReviewUrl}}", "{{unsubscribeUrl}}");
            // Yelp removed: "{{yelpReviewUrl}}"
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

    // UPDATED: processTemplate method with smart Google URL generation
    public String processTemplate(String template, Customer customer, Business business, String reviewLink) {
        if (template == null) {
            return "";
        }

        // UPDATED PROCESSING FOR MULTI-PLATFORM WITH SMART FALLBACKS
        String result = template
                .replace("{{customerName}}", customer.getName() != null ? customer.getName() : "")
                .replace("{{businessName}}", business.getName() != null ? business.getName() : "")
                .replace("{{serviceType}}", customer.getServiceType() != null ? customer.getServiceType() : "")
                .replace("{{serviceDate}}", customer.getServiceDate() != null ? customer.getServiceDate().toString() : "")
                .replace("{{businessPhone}}", business.getPhone() != null ? business.getPhone() : "")
                .replace("{{businessWebsite}}", business.getWebsite() != null ? business.getWebsite() : "")
                .replace("{{reviewLink}}", reviewLink != null ? reviewLink : ""); // Keep for backward compatibility

        // SMART PLATFORM URL PROCESSING WITH FALLBACKS
        String googleReviewUrl = generateGoogleReviewUrl(business);
        String facebookReviewUrl = business.getFacebookPageUrl() != null ?
                business.getFacebookPageUrl() + "/reviews" : "#";
        // String yelpReviewUrl = business.getYelpPageUrl() != null ? business.getYelpPageUrl() : "#"; // Commented out for future use
        String privateReviewUrl = "https://reputul.com/feedback/" + customer.getId();
        String unsubscribeUrl = "https://reputul.com/unsubscribe/" + customer.getId();

        result = result
                .replace("{{googleReviewUrl}}", googleReviewUrl)
                .replace("{{facebookReviewUrl}}", facebookReviewUrl)
                // .replace("{{yelpReviewUrl}}", yelpReviewUrl) // Commented out for future use
                .replace("{{privateReviewUrl}}", privateReviewUrl)
                .replace("{{unsubscribeUrl}}", unsubscribeUrl);

        return result;
    }
}