package com.reputul.backend.services;

import com.reputul.backend.dto.EmailTemplateStyleDto;
import com.reputul.backend.dto.UpdateEmailTemplateStyleRequest;
import com.reputul.backend.models.EmailTemplateStyle;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateStyleRepository;
import com.reputul.backend.repositories.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailTemplateStyleService {

    private final EmailTemplateStyleRepository styleRepository;
    private final OrganizationRepository organizationRepository;

    @Transactional(readOnly = true)
    public EmailTemplateStyleDto getOrganizationStyle(User user) {
        Organization organization = user.getOrganization();

        EmailTemplateStyle style = styleRepository.findByOrganization(organization)
                .orElseGet(() -> createDefaultStyle(organization));

        return convertToDto(style);
    }

    @Transactional
    public EmailTemplateStyleDto createOrUpdateStyle(User user, UpdateEmailTemplateStyleRequest request) {
        Organization organization = user.getOrganization();

        EmailTemplateStyle style = styleRepository.findByOrganization(organization)
                .orElse(EmailTemplateStyle.builder()
                        .organization(organization)
                        .build());

        updateStyleFromRequest(style, request);
        EmailTemplateStyle savedStyle = styleRepository.save(style);

        log.info("Updated email template style for organization {}", organization.getId());
        return convertToDto(savedStyle);
    }

    @Transactional
    public EmailTemplateStyle createDefaultStyle(Organization organization) {
        if (styleRepository.existsByOrganization(organization)) {
            return styleRepository.findByOrganization(organization).get();
        }

        EmailTemplateStyle style = EmailTemplateStyle.builder()
                .organization(organization)
                .logoSize(EmailTemplateStyle.LogoSize.SMALL)
                .logoPosition(EmailTemplateStyle.Position.LEFT)
                .showBusinessName(true)
                .businessNamePosition(EmailTemplateStyle.Position.CENTER)
                .showCustomImage(false)
                .textAlignment(EmailTemplateStyle.Position.LEFT)
                .buttonText("Leave Feedback")
                .buttonAlignment(EmailTemplateStyle.Position.CENTER)
                .buttonStyle(EmailTemplateStyle.ButtonStyle.ROUNDED)
                .buttonColor("#00D682")
                .backgroundColor("#F2F2F7")
                .containerBackgroundColor("#FFFFFF")
                .containerCorners(EmailTemplateStyle.CornerStyle.ROUNDED)
                .primaryColor("#00D682")
                .secondaryColor("#333333")
                .textColor("#333333")
                .build();

        EmailTemplateStyle savedStyle = styleRepository.save(style);
        log.info("Created default email template style for organization {}", organization.getId());
        return savedStyle;
    }

    @Transactional
    public void resetToDefaults(User user) {
        Organization organization = user.getOrganization();
        styleRepository.findByOrganization(organization).ifPresent(style -> {
            style.setLogoSize(EmailTemplateStyle.LogoSize.SMALL);
            style.setLogoPosition(EmailTemplateStyle.Position.LEFT);
            style.setShowBusinessName(true);
            style.setBusinessNamePosition(EmailTemplateStyle.Position.CENTER);
            style.setShowCustomImage(false);
            style.setTextAlignment(EmailTemplateStyle.Position.LEFT);
            style.setButtonText("Leave Feedback");
            style.setButtonAlignment(EmailTemplateStyle.Position.CENTER);
            style.setButtonStyle(EmailTemplateStyle.ButtonStyle.ROUNDED);
            style.setButtonColor("#00D682");
            style.setBackgroundColor("#F2F2F7");
            style.setContainerBackgroundColor("#FFFFFF");
            style.setContainerCorners(EmailTemplateStyle.CornerStyle.ROUNDED);
            style.setPrimaryColor("#00D682");
            style.setSecondaryColor("#333333");
            style.setTextColor("#333333");
            styleRepository.save(style);
            log.info("Reset email template style to defaults for organization {}", organization.getId());
        });
    }

    private void updateStyleFromRequest(EmailTemplateStyle style, UpdateEmailTemplateStyleRequest request) {
        if (request.getLogoUrl() != null) style.setLogoUrl(request.getLogoUrl());
        if (request.getLogoSize() != null) style.setLogoSize(request.getLogoSize());
        if (request.getLogoPosition() != null) style.setLogoPosition(request.getLogoPosition());
        if (request.getShowBusinessName() != null) style.setShowBusinessName(request.getShowBusinessName());
        if (request.getBusinessNamePosition() != null) style.setBusinessNamePosition(request.getBusinessNamePosition());
        if (request.getCustomImageUrl() != null) style.setCustomImageUrl(request.getCustomImageUrl());
        if (request.getShowCustomImage() != null) style.setShowCustomImage(request.getShowCustomImage());
        if (request.getTextAlignment() != null) style.setTextAlignment(request.getTextAlignment());
        if (request.getButtonText() != null) style.setButtonText(request.getButtonText());
        if (request.getButtonAlignment() != null) style.setButtonAlignment(request.getButtonAlignment());
        if (request.getButtonStyle() != null) style.setButtonStyle(request.getButtonStyle());
        if (request.getButtonColor() != null) style.setButtonColor(request.getButtonColor());
        if (request.getBackgroundColor() != null) style.setBackgroundColor(request.getBackgroundColor());
        if (request.getContainerBackgroundColor() != null) style.setContainerBackgroundColor(request.getContainerBackgroundColor());
        if (request.getContainerCorners() != null) style.setContainerCorners(request.getContainerCorners());
        if (request.getPrimaryColor() != null) style.setPrimaryColor(request.getPrimaryColor());
        if (request.getSecondaryColor() != null) style.setSecondaryColor(request.getSecondaryColor());
        if (request.getTextColor() != null) style.setTextColor(request.getTextColor());
    }

    private EmailTemplateStyleDto convertToDto(EmailTemplateStyle style) {
        return EmailTemplateStyleDto.builder()
                .id(style.getId())
                .organizationId(style.getOrganization().getId())
                .logoUrl(style.getLogoUrl())
                .logoSize(style.getLogoSize())
                .logoPosition(style.getLogoPosition())
                .showBusinessName(style.getShowBusinessName())
                .businessNamePosition(style.getBusinessNamePosition())
                .customImageUrl(style.getCustomImageUrl())
                .showCustomImage(style.getShowCustomImage())
                .textAlignment(style.getTextAlignment())
                .buttonText(style.getButtonText())
                .buttonAlignment(style.getButtonAlignment())
                .buttonStyle(style.getButtonStyle())
                .buttonColor(style.getButtonColor())
                .backgroundColor(style.getBackgroundColor())
                .containerBackgroundColor(style.getContainerBackgroundColor())
                .containerCorners(style.getContainerCorners())
                .primaryColor(style.getPrimaryColor())
                .secondaryColor(style.getSecondaryColor())
                .textColor(style.getTextColor())
                .createdAt(style.getCreatedAt())
                .updatedAt(style.getUpdatedAt())
                .build();
    }
}