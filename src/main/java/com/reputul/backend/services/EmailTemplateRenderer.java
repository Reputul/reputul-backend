package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.EmailTemplateStyle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service responsible for rendering email templates with applied styles.
 * Generates HTML emails dynamically based on template content and organization styling.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateRenderer {

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Renders an email template with applied styling and variable replacement
     */
    public String renderTemplate(EmailTemplate template, EmailTemplateStyle style, Map<String, String> variables) {
        if (template.getSimplifiedMode() != null && template.getSimplifiedMode()) {
            return renderSimplifiedTemplate(template, style, variables);
        } else {
            // Fallback to legacy multi-platform template
            return renderLegacyTemplate(template, variables);
        }
    }

    /**
     * Renders simplified single-button template with custom styling
     */
    private String renderSimplifiedTemplate(EmailTemplate template, EmailTemplateStyle style, Map<String, String> variables) {
        // Get styling values with defaults
        String logoUrl = style.getLogoUrl();
        String logoSize = getLogoSizePixels(style.getLogoSize());
        String logoAlign = getAlignmentStyle(style.getLogoPosition());
        boolean showBusinessName = style.getShowBusinessName() != null ? style.getShowBusinessName() : true;
        String businessNameAlign = getAlignmentStyle(style.getBusinessNamePosition());
        String customImageUrl = style.getCustomImageUrl();
        boolean showCustomImage = style.getShowCustomImage() != null && style.getShowCustomImage();
        String textAlign = getAlignmentStyle(style.getTextAlignment());
        String buttonText = style.getButtonText() != null ? style.getButtonText() : "Leave Feedback";
        String buttonAlign = getAlignmentStyle(style.getButtonAlignment());
        String buttonBorderRadius = getButtonBorderRadius(style.getButtonStyle());
        String buttonColor = style.getButtonColor() != null ? style.getButtonColor() : "#00D682";
        String bgColor = style.getBackgroundColor() != null ? style.getBackgroundColor() : "#F2F2F7";
        String containerBgColor = style.getContainerBackgroundColor() != null ? style.getContainerBackgroundColor() : "#FFFFFF";
        String containerBorderRadius = getContainerBorderRadius(style.getContainerCorners());
        String textColor = style.getTextColor() != null ? style.getTextColor() : "#333333";
        String primaryColor = style.getPrimaryColor() != null ? style.getPrimaryColor() : "#00D682";

        // Get body content and process it intelligently
        String rawBody = template.getBody() != null ? template.getBody() : "";
        String bodyContent;

        // Check if body already contains HTML tags
        if (isHtmlContent(rawBody)) {
            // Already HTML - just replace variables
            bodyContent = replaceVariables(rawBody, variables);
        } else {
            // Plain text - convert to nice HTML with paragraphs
            bodyContent = convertPlainTextToHtml(replaceVariables(rawBody, variables));
        }

        String businessName = variables.getOrDefault("businessName", "Our Business");
        String buttonUrl = variables.getOrDefault("privateFeedbackUrl", frontendUrl + "/feedback/unknown");

        // Build HTML email
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Transitional//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd\">\n");
        html.append("<html xmlns=\"http://www.w3.org/1999/xhtml\">\n");
        html.append("<head>\n");
        html.append("    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n");
        html.append("    <title>").append(escapeHtml(variables.getOrDefault("subject", "Feedback Request"))).append("</title>\n");
        html.append("</head>\n");
        html.append("<body style=\"margin: 0; padding: 0; font-family: Arial, Helvetica, sans-serif; line-height: 1.6; color: ").append(textColor).append("; background-color: ").append(bgColor).append(";\">\n");
        html.append("    <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" style=\"background-color: ").append(bgColor).append("; min-height: 100%;\">\n");
        html.append("        <tr>\n");
        html.append("            <td align=\"center\" style=\"padding: 40px 20px;\">\n");
        html.append("                <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"600\" style=\"max-width: 600px; background-color: ").append(containerBgColor).append("; border-radius: ").append(containerBorderRadius).append("; box-shadow: 0 2px 10px rgba(0,0,0,0.1);\">\n");

        // Header with logo and business name
        html.append("                    <!-- Header -->\n");
        html.append("                    <tr>\n");
        html.append("                        <td style=\"padding: 40px 30px; text-align: ").append(businessNameAlign).append("; border-bottom: 1px solid #e0e0e0;\">\n");

        // Logo
        if (logoUrl != null && !logoUrl.trim().isEmpty()) {
            html.append("                            <div style=\"text-align: ").append(logoAlign).append("; margin-bottom: ").append(showBusinessName ? "15px" : "0").append(";\">\n");
            html.append("                                <img src=\"").append(escapeHtml(logoUrl)).append("\" alt=\"Logo\" style=\"max-width: ").append(logoSize).append("; height: auto; display: inline-block;\" />\n");
            html.append("                            </div>\n");
        }

        // Business Name
        if (showBusinessName) {
            html.append("                            <h1 style=\"margin: 0; font-size: 28px; font-weight: bold; color: ").append(primaryColor).append(";\">").append(escapeHtml(businessName)).append("</h1>\n");
        }

        html.append("                        </td>\n");
        html.append("                    </tr>\n");

        // Custom Image (if enabled)
        if (showCustomImage && customImageUrl != null && !customImageUrl.trim().isEmpty()) {
            html.append("                    <!-- Custom Image -->\n");
            html.append("                    <tr>\n");
            html.append("                        <td style=\"padding: 0;\">\n");
            html.append("                            <img src=\"").append(escapeHtml(customImageUrl)).append("\" alt=\"\" style=\"width: 100%; height: auto; display: block;\" />\n");
            html.append("                        </td>\n");
            html.append("                    </tr>\n");
        }

        // Main Content
        html.append("                    <!-- Main Content -->\n");
        html.append("                    <tr>\n");
        html.append("                        <td style=\"padding: 40px 30px; text-align: ").append(textAlign).append(";\">\n");
        html.append("                            <div style=\"font-size: 16px; line-height: 1.6; color: ").append(textColor).append(";\">\n");
        html.append(bodyContent);  // User's custom content
        html.append("                            </div>\n");
        html.append("                        </td>\n");
        html.append("                    </tr>\n");

        // CTA Button
        html.append("                    <!-- CTA Button -->\n");
        html.append("                    <tr>\n");
        html.append("                        <td style=\"padding: 0 30px 40px 30px; text-align: ").append(buttonAlign).append(";\">\n");
        html.append("                            <table cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin: 0 auto;\">\n");
        html.append("                                <tr>\n");
        html.append("                                    <td>\n");
        html.append("                                        <a href=\"").append(escapeHtml(buttonUrl)).append("\" style=\"display: inline-block; background-color: ").append(buttonColor).append("; color: #ffffff; padding: 16px 40px; text-decoration: none; border-radius: ").append(buttonBorderRadius).append("; font-weight: bold; font-size: 18px;\">").append(escapeHtml(buttonText)).append("</a>\n");
        html.append("                                    </td>\n");
        html.append("                                </tr>\n");
        html.append("                            </table>\n");
        html.append("                        </td>\n");
        html.append("                    </tr>\n");

        // Footer
        html.append("                    <!-- Footer -->\n");
        html.append("                    <tr>\n");
        html.append("                        <td style=\"background-color: #f8f9fa; padding: 25px 30px; border-top: 1px solid #e0e0e0; text-align: center; border-radius: 0 0 ").append(containerBorderRadius).append(" ").append(containerBorderRadius).append(";\">\n");

        String businessPhone = variables.getOrDefault("businessPhone", "");
        String businessWebsite = variables.getOrDefault("businessWebsite", "");

        if (!businessPhone.isEmpty() || !businessWebsite.isEmpty()) {
            html.append("                            <p style=\"margin: 0 0 15px 0; font-size: 14px; color: #666666;\">\n");
            if (!businessPhone.isEmpty()) {
                html.append("                                ").append(escapeHtml(businessPhone));
                if (!businessWebsite.isEmpty()) {
                    html.append(" | ");
                }
            }
            if (!businessWebsite.isEmpty()) {
                html.append(escapeHtml(businessWebsite));
            }
            html.append("\n                            </p>\n");
        }

        html.append("                            <p style=\"margin: 0; font-size: 12px; color: #999999;\">\n");
        html.append("                                <a href=\"").append(escapeHtml(variables.getOrDefault("unsubscribeUrl", "#"))).append("\" style=\"color: #666666; text-decoration: underline;\">Unsubscribe</a>\n");
        html.append("                            </p>\n");
        html.append("                        </td>\n");
        html.append("                    </tr>\n");

        html.append("                </table>\n");
        html.append("            </td>\n");
        html.append("        </tr>\n");
        html.append("    </table>\n");
        html.append("</body>\n");
        html.append("</html>");

        return html.toString();
    }

    /**
     * Renders legacy multi-platform template (for backward compatibility)
     */
    private String renderLegacyTemplate(EmailTemplate template, Map<String, String> variables) {
        return replaceVariables(template.getBody(), variables);
    }

    /**
     * Replaces template variables with actual values
     */
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

    // Helper methods for style conversion
    private String getLogoSizePixels(EmailTemplateStyle.LogoSize size) {
        if (size == null) return "120px";
        return switch (size) {
            case SMALL -> "120px";
            case MEDIUM -> "180px";
            case LARGE -> "240px";
        };
    }

    private String getAlignmentStyle(EmailTemplateStyle.Position position) {
        if (position == null) return "left";
        return switch (position) {
            case LEFT -> "left";
            case CENTER -> "center";
            case RIGHT -> "right";
        };
    }

    private String getButtonBorderRadius(EmailTemplateStyle.ButtonStyle style) {
        if (style == null) return "8px";
        return switch (style) {
            case ROUNDED -> "8px";
            case PILL -> "50px";
        };
    }

    private String getContainerBorderRadius(EmailTemplateStyle.CornerStyle style) {
        if (style == null) return "12px";
        return switch (style) {
            case ROUNDED -> "12px";
            case SHARP -> "0px";
        };
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Checks if content contains HTML tags
     */
    private boolean isHtmlContent(String content) {
        if (content == null) return false;
        String trimmed = content.trim();
        // Check for common HTML indicators
        return trimmed.contains("<html") ||
                trimmed.contains("<!DOCTYPE") ||
                trimmed.contains("<body") ||
                trimmed.contains("<div") ||
                trimmed.contains("<p") ||
                trimmed.contains("<table");
    }

    /**
     * Converts plain text to nicely formatted HTML with paragraphs
     */
    private String convertPlainTextToHtml(String plainText) {
        if (plainText == null || plainText.trim().isEmpty()) {
            return "<p>No content</p>";
        }

        StringBuilder html = new StringBuilder();
        String[] paragraphs = plainText.split("\n\n+"); // Split on double newlines

        for (String para : paragraphs) {
            para = para.trim();
            if (!para.isEmpty()) {
                // Replace single newlines with <br> within paragraphs
                String formatted = para.replace("\n", "<br>\n");
                html.append("<p style=\"margin: 0 0 15px 0;\">").append(formatted).append("</p>\n");
            }
        }

        return html.toString();
    }
}