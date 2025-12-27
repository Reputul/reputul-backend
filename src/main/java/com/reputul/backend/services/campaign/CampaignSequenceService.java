package com.reputul.backend.services.campaign;

import com.reputul.backend.dto.campaign.request.CreateSequenceRequest;
import com.reputul.backend.dto.campaign.request.CreateStepRequest;
import com.reputul.backend.dto.campaign.request.UpdateSequenceRequest;
import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignStep;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.enums.MessageType;
import com.reputul.backend.repositories.campaign.CampaignSequenceRepository;
import com.reputul.backend.repositories.campaign.CampaignStepRepository;
import com.reputul.backend.repositories.EmailTemplateRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CampaignSequenceService {

    private final CampaignSequenceRepository sequenceRepository;
    private final CampaignStepRepository stepRepository;
    private final EmailTemplateRepository emailTemplateRepository;

    /**
     * Create a new campaign sequence with steps
     */
    public CampaignSequence createSequence(Long orgId, CreateSequenceRequest request) {
        log.info("Creating campaign sequence '{}' for org {}", request.getName(), orgId);

        // Validate request
        validateSequenceRequest(request);

        // Check if name already exists for this org
        if (sequenceRepository.existsByOrgIdAndName(orgId, request.getName())) {
            throw new IllegalArgumentException("Campaign sequence with name '" + request.getName() + "' already exists");
        }

        // Create sequence
        CampaignSequence sequence = new CampaignSequence();
        sequence.setOrgId(orgId);
        sequence.setName(request.getName());
        sequence.setDescription(request.getDescription());
        sequence.setIsDefault(request.getIsDefault() != null ? request.getIsDefault() : false);
        sequence.setIsActive(true);

        // If this is set as default, unset other defaults
        if (sequence.getIsDefault()) {
            unsetOtherDefaults(orgId);
        }

        sequence = sequenceRepository.save(sequence);

        // Create steps
        if (request.getSteps() != null && !request.getSteps().isEmpty()) {
            for (CreateStepRequest stepRequest : request.getSteps()) {
                CampaignStep step = createStep(sequence, stepRequest);
                sequence.addStep(step);
            }
        }

        log.info("Created campaign sequence {} with {} steps", sequence.getId(), sequence.getStepCount());
        return sequence;
    }

    /**
     * Get default sequence for organization, creating one if it doesn't exist
     */
    public CampaignSequence getDefaultSequence(Long orgId) {
        List<CampaignSequence> defaultSequences = sequenceRepository.findByOrgIdAndIsDefaultTrue(orgId);

        if (!defaultSequences.isEmpty()) {
            if (defaultSequences.size() > 1) {
                log.warn("Found {} default sequences for org {}. Returning first one.",
                        defaultSequences.size(), orgId);
            }
            return defaultSequences.getFirst();
        }

        // No default found - create preset campaigns including default
        log.info("No campaigns found for org {}. Creating preset campaigns.", orgId);
        createPresetCampaigns(orgId);

        // Return the newly created default
        return sequenceRepository.findByOrgIdAndIsDefaultTrue(orgId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Failed to create default campaign"));
    }

    /**
     * NEW: Create all 4 preset campaigns for an organization
     * Called when org has no campaigns yet
     */
    public void createPresetCampaigns(Long orgId) {
        long existingCount = sequenceRepository.countByOrgId(orgId);
        if (existingCount > 0) {
            log.info("Org {} already has {} campaigns, skipping preset creation", orgId, existingCount);
            return;
        }

        log.info("Creating 4 preset campaigns for org {}", orgId);

        // Campaign 1: Get Reviews (DEFAULT)
        createSequence(orgId, buildGetReviewsCampaign());

        // Campaign 2: Follow-Up Sequence
        createSequence(orgId, buildFollowUpCampaign());

        // Campaign 3: Win-Back Campaign
        createSequence(orgId, buildWinBackCampaign());

        // Campaign 4: Referral Request
        createSequence(orgId, buildReferralCampaign());

        log.info("Successfully created 4 preset campaigns for org {}", orgId);
    }

    /**
     * Get sequence by ID with steps loaded
     */
    public CampaignSequence getSequenceWithSteps(Long sequenceId) {
        return sequenceRepository.findByIdWithSteps(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign sequence not found: " + sequenceId));
    }

    /**
     * Get all active sequences for organization
     */
    public List<CampaignSequence> getActiveSequences(Long orgId) {
        return sequenceRepository.findByOrgIdAndIsActiveTrue(orgId);
    }

    /**
     * Get all sequences for organization with their steps
     */
    public List<CampaignSequence> getSequencesWithSteps(Long orgId) {
        long count = sequenceRepository.countByOrgId(orgId);

        if (count == 0) {
            log.info("No campaigns found for org {}, creating presets", orgId);
            createPresetCampaigns(orgId);
        }

        return sequenceRepository.findByOrgIdWithSteps(orgId);
    }


    /**
     * Update an existing sequence
     */
    public CampaignSequence updateSequence(Long sequenceId, UpdateSequenceRequest request) {
        CampaignSequence sequence = sequenceRepository.findById(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign sequence not found: " + sequenceId));

        log.info("Updating campaign sequence {}", sequenceId);

        // Update basic fields
        if (request.getName() != null) {
            // Check for name conflicts
            if (!sequence.getName().equals(request.getName()) &&
                    sequenceRepository.existsByOrgIdAndName(sequence.getOrgId(), request.getName())) {
                throw new IllegalArgumentException("Campaign sequence with name '" + request.getName() + "' already exists");
            }
            sequence.setName(request.getName());
        }

        if (request.getDescription() != null) {
            sequence.setDescription(request.getDescription());
        }

        if (request.getIsActive() != null) {
            sequence.setIsActive(request.getIsActive());
        }

        // Handle default flag
        if (request.getIsDefault() != null && request.getIsDefault() && !sequence.getIsDefault()) {
            unsetOtherDefaults(sequence.getOrgId());
            sequence.setIsDefault(true);
        } else if (request.getIsDefault() != null && !request.getIsDefault() && sequence.getIsDefault()) {
            sequence.setIsDefault(false);
        }

        return sequenceRepository.save(sequence);
    }

    /**
     * Delete a sequence (soft delete by marking inactive)
     */
    public void deleteSequence(Long sequenceId) {
        CampaignSequence sequence = sequenceRepository.findById(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign sequence not found: " + sequenceId));

        log.info("Deleting campaign sequence {}", sequenceId);

        // Can't delete the default sequence if it's the only one
        if (sequence.getIsDefault()) {
            long totalSequences = sequenceRepository.countByOrgId(sequence.getOrgId());
            if (totalSequences == 1) {
                throw new IllegalStateException("Cannot delete the only campaign sequence for this organization");
            }
        }

        sequence.setIsActive(false);
        sequenceRepository.save(sequence);
    }

    /**
     * Add a step to an existing sequence
     */
    public CampaignStep addStep(Long sequenceId, CreateStepRequest stepRequest) {
        CampaignSequence sequence = getSequenceWithSteps(sequenceId);

        log.info("Adding step {} to sequence {}", stepRequest.getStepNumber(), sequenceId);

        CampaignStep step = createStep(sequence, stepRequest);
        sequence.addStep(step);

        return step;
    }

    /**
     * Remove a step from a sequence
     */
    public void removeStep(Long stepId) {
        CampaignStep step = stepRepository.findById(stepId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign step not found: " + stepId));

        log.info("Removing step {} from sequence {}", stepId, step.getSequence().getId());

        stepRepository.delete(step);
    }

    /**
     * Update sequence active/inactive status
     */
    public CampaignSequence updateSequenceStatus(Long sequenceId, Boolean isActive) {
        CampaignSequence sequence = sequenceRepository.findByIdWithSteps(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Sequence not found"));

        sequence.setIsActive(isActive);
        sequence.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        return sequence;
    }


    /**
     * Set a sequence as default for an organization
     * Unsets any other default sequences for the same org
     */
    @Transactional
    public CampaignSequence setAsDefault(Long sequenceId, Long orgId) {
        // First, unset all default sequences for this org
        List<CampaignSequence> existingDefaults = sequenceRepository
                .findByOrgIdAndIsDefaultTrue(orgId);

        for (CampaignSequence existing : existingDefaults) {
            existing.setIsDefault(false);
            existing.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            sequenceRepository.save(existing);
        }

        // Now set the new default
        CampaignSequence sequence = sequenceRepository.findById(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Sequence not found"));

        // Verify ownership
        if (!sequence.getOrgId().equals(orgId)) {
            throw new IllegalStateException("Sequence does not belong to this organization");
        }

        sequence.setIsDefault(true);
        sequence.setIsActive(true); // Auto-activate when set as default
        sequence.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        return sequenceRepository.save(sequence);
    }

    // ================================================================
    // PRESET CAMPAIGN BUILDERS
    // ================================================================

    /**
     * Campaign 1: Get Reviews (Default Campaign)
     * Objective: Convert happy customers into 5-star Google reviews
     * Entry: Automatically triggered when review request is created
     */
    private CreateSequenceRequest buildGetReviewsCampaign() {
        CreateSequenceRequest request = new CreateSequenceRequest();
        request.setName("Get Reviews");
        request.setDescription("Proven 4-step sequence: SMS + 3 professional emails to maximize review collection");
        request.setIsDefault(true); // This is the default campaign

        List<CreateStepRequest> steps = List.of(
                // Step 1: Immediate SMS (simple, friendly, personal)
                createStepRequest(1, 0, MessageType.SMS, null,
                        "Hi {{customer_name}}! Thanks for choosing {{business_name}}. We'd love your feedback: {{review_link}} Reply STOP to opt out."),

                // Step 2: Professional email after 3 days - Uses "Initial Review Request" template
                createStepRequestWithTemplate(2, 72, MessageType.EMAIL_PROFESSIONAL,
                        "We'd love your feedback, {{customer_name}}!",
                        "INITIAL_REQUEST"), // Will map to template type

                // Step 3: Follow-up email after 7 days - Uses "7-Day Follow-up" template
                createStepRequestWithTemplate(3, 168, MessageType.EMAIL_PROFESSIONAL,
                        "Your opinion matters - {{business_name}}",
                        "FOLLOW_UP_7_DAY"),

                // Step 4: Final email after 14 days - Uses "14-Day Final Follow-up" template
                createStepRequestWithTemplate(4, 336, MessageType.EMAIL_PROFESSIONAL,
                        "Final request: Share your {{business_name}} experience",
                        "FOLLOW_UP_14_DAY")
        );

        request.setSteps(steps);
        return request;
    }

    /**
     * Campaign 2: Follow-Up Sequence
     * Objective: Re-engage customers who didn't respond to initial request
     * Entry: Manually triggered for customers with no review after 7 days
     */
    private CreateSequenceRequest buildFollowUpCampaign() {
        CreateSequenceRequest request = new CreateSequenceRequest();
        request.setName("Follow-Up Sequence");
        request.setDescription("Professional follow-up for customers who haven't responded yet");
        request.setIsDefault(false);

        List<CreateStepRequest> steps = List.of(
                // Step 1: Professional email reminder
                createStepRequest(1, 0, MessageType.EMAIL_PROFESSIONAL,
                        "How was your experience with {{business_name}}?",
                        buildEmailTemplate("followup_professional")),

                // Step 2: Friendly SMS nudge after 3 days
                createStepRequest(2, 72, MessageType.SMS, null,
                        "Hey {{customer_name}}! Quick reminder - would love to hear about your experience with {{business_name}}. Takes just 1 min: {{review_link}}"),

                // Step 3: Final ask with gratitude after 7 days
                createStepRequest(3, 168, MessageType.EMAIL_PROFESSIONAL,
                        "Last chance to share your feedback!",
                        buildEmailTemplate("followup_final"))
        );

        request.setSteps(steps);
        return request;
    }

    /**
     * Campaign 3: Win-Back Campaign
     * Objective: Re-engage customers who haven't interacted in 30+ days
     * Entry: Manually triggered for inactive customers
     */
    private CreateSequenceRequest buildWinBackCampaign() {
        CreateSequenceRequest request = new CreateSequenceRequest();
        request.setName("Win-Back Campaign");
        request.setDescription("Re-engage past customers and remind them why they loved your service");
        request.setIsDefault(false);

        List<CreateStepRequest> steps = List.of(
                // Step 1: "We miss you" email
                createStepRequest(1, 0, MessageType.EMAIL_PROFESSIONAL,
                        "We miss you, {{customer_name}}!",
                        buildEmailTemplate("winback_initial")),

                // Step 2: Special offer reminder after 3 days
                createStepRequest(2, 72, MessageType.SMS, null,
                        "Hey {{customer_name}}! Don't forget - 10% off your next service at {{business_name}}. Book today! {{business_phone}}"),

                // Step 3: Customer success stories after 7 days
                createStepRequest(3, 168, MessageType.EMAIL_PROFESSIONAL,
                        "See what our customers are saying...",
                        buildEmailTemplate("winback_social_proof"))
        );

        request.setSteps(steps);
        return request;
    }

    /**
     * Campaign 4: Referral Request
     * Objective: Turn happy customers into brand advocates
     * Entry: Triggered when customer leaves 4+ star review
     */
    private CreateSequenceRequest buildReferralCampaign() {
        CreateSequenceRequest request = new CreateSequenceRequest();
        request.setName("Referral Request");
        request.setDescription("Ask happy reviewers to refer their friends and family");
        request.setIsDefault(false);

        List<CreateStepRequest> steps = List.of(
                // Step 1: Thank you + referral ask (1 day after positive review)
                createStepRequest(1, 24, MessageType.EMAIL_PROFESSIONAL,
                        "Thank you for your amazing review!",
                        buildEmailTemplate("referral_thankyou")),

                // Step 2: Referral incentive reminder after 5 days
                createStepRequest(2, 120, MessageType.SMS, null,
                        "Thanks again for your 5-star review! Remember: Refer a friend to {{business_name}} and you both save $25! {{referral_link}}")
        );

        request.setSteps(steps);
        return request;
    }

    // ================================================================
    // PRIVATE HELPER METHODS
    // ================================================================

    private void validateSequenceRequest(CreateSequenceRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Campaign sequence name is required");
        }

        if (request.getSteps() != null) {
            for (CreateStepRequest stepRequest : request.getSteps()) {
                validateStepRequest(stepRequest);
            }
        }
    }

    private void validateStepRequest(CreateStepRequest stepRequest) {
        if (stepRequest.getStepNumber() == null || stepRequest.getStepNumber() < 1) {
            throw new IllegalArgumentException("Step number must be positive");
        }

        if (stepRequest.getDelayHours() == null || stepRequest.getDelayHours() < 0) {
            throw new IllegalArgumentException("Delay hours cannot be negative");
        }

        if (stepRequest.getMessageType() == null) {
            throw new IllegalArgumentException("Message type is required");
        }

        // Body template is required UNLESS using email template type (for email steps)
        boolean hasBodyTemplate = stepRequest.getBodyTemplate() != null && !stepRequest.getBodyTemplate().trim().isEmpty();
        boolean hasEmailTemplateType = stepRequest.getEmailTemplateType() != null && !stepRequest.getEmailTemplateType().trim().isEmpty();

        if (!hasBodyTemplate && !hasEmailTemplateType) {
            throw new IllegalArgumentException("Body template or email template type is required");
        }

        // Email messages require a subject
        if (stepRequest.getMessageType().isEmail() &&
                (stepRequest.getSubjectTemplate() == null || stepRequest.getSubjectTemplate().trim().isEmpty())) {
            throw new IllegalArgumentException("Subject template is required for email messages");
        }
    }

    private void unsetOtherDefaults(Long orgId) {
        List<CampaignSequence> defaultSequences = sequenceRepository.findByOrgIdAndIsDefaultTrue(orgId);

        for (CampaignSequence defaultSequence : defaultSequences) {
            defaultSequence.setIsDefault(false);
            defaultSequence.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            sequenceRepository.save(defaultSequence);
        }
    }

    private CampaignStep createStep(CampaignSequence sequence, CreateStepRequest stepRequest) {
        CampaignStep step = new CampaignStep();
        step.setSequence(sequence);
        step.setStepNumber(stepRequest.getStepNumber());
        step.setDelayHours(stepRequest.getDelayHours());
        step.setMessageType(stepRequest.getMessageType());
        step.setSubjectTemplate(stepRequest.getSubjectTemplate());

        // Set bodyTemplate - use placeholder if using email template type
        if (stepRequest.getBodyTemplate() != null && !stepRequest.getBodyTemplate().trim().isEmpty()) {
            step.setBodyTemplate(stepRequest.getBodyTemplate());
        } else if (stepRequest.getEmailTemplateType() != null) {
            // Placeholder for email steps using templates
            step.setBodyTemplate("[Uses Email Template: " + stepRequest.getEmailTemplateType() + "]");
        } else {
            step.setBodyTemplate(""); // Empty fallback
        }

        step.setIsActive(true);

        // NEW: Resolve email template type to actual template ID
        if (stepRequest.getEmailTemplateType() != null && stepRequest.getMessageType().isEmail()) {
            try {
                EmailTemplate.TemplateType templateType = EmailTemplate.TemplateType.valueOf(stepRequest.getEmailTemplateType());

                // Find the default template of this type for the organization
                EmailTemplate template = emailTemplateRepository.findByOrgIdAndTypeAndIsDefaultTrue(
                                sequence.getOrgId(), templateType)
                        .stream()
                        .findFirst()
                        .orElse(null);

                if (template != null) {
                    step.setEmailTemplateId(template.getId());
                    log.info("Linked campaign step {} to email template {} (type: {})",
                            stepRequest.getStepNumber(), template.getId(), templateType);
                } else {
                    log.warn("No default email template found for type {} in org {}. Step will use fallback body template.",
                            templateType, sequence.getOrgId());
                }
            } catch (Exception e) {
                // Don't fail campaign creation if template linking fails
                log.error("Failed to link email template for step {}: {}. Continuing without template link.",
                        stepRequest.getStepNumber(), e.getMessage());
            }
        }

        return stepRepository.save(step);
    }

    private CreateStepRequest createStepRequest(Integer stepNumber, Integer delayHours,
                                                MessageType messageType, String subject, String body) {
        CreateStepRequest request = new CreateStepRequest();
        request.setStepNumber(stepNumber);
        request.setDelayHours(delayHours);
        request.setMessageType(messageType);
        request.setSubjectTemplate(subject);
        request.setBodyTemplate(body);
        return request;
    }

    /**
     * NEW: Create step request with email template type reference
     * This will be resolved to actual template ID when creating the step
     */
    private CreateStepRequest createStepRequestWithTemplate(Integer stepNumber, Integer delayHours,
                                                            MessageType messageType, String subject, String templateType) {
        CreateStepRequest request = new CreateStepRequest();
        request.setStepNumber(stepNumber);
        request.setDelayHours(delayHours);
        request.setMessageType(messageType);
        request.setSubjectTemplate(subject);
        request.setEmailTemplateType(templateType); // NEW: Store template type for lookup
        return request;
    }

    /**
     * Build HTML email templates for campaigns
     * Templates use snake_case variables: {{customer_name}}, {{business_name}}, etc.
     */
    private String buildEmailTemplate(String templateType) {
        return switch (templateType) {
            case "followup_professional" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>How was your experience?</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #4F46E5;">Hi {{customer_name}},</h2>
                    
                    <p>We noticed you haven't had a chance to share your feedback yet. Your opinion really matters to us!</p>
                    
                    <p>Would you mind taking 2 minutes to leave us a review?</p>
                    
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="{{review_link}}" style="background: #4F46E5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; display: inline-block; font-weight: bold;">Leave a Review</a>
                    </div>
                    
                    <p>Thanks for choosing {{business_name}}!</p>
                    
                    <p style="color: #666; font-size: 14px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
                        {{business_name}}<br>
                        {{business_phone}}<br>
                        {{business_website}}
                    </p>
                </body>
                </html>
                """;

            case "followup_final" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Last chance to share your feedback!</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #4F46E5;">Hi {{customer_name}},</h2>
                    
                    <p>This is our final reminder! We'd really appreciate hearing about your experience with {{business_name}}.</p>
                    
                    <p>Your feedback helps us improve and helps other customers make informed decisions.</p>
                    
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="{{review_link}}" style="background: #4F46E5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; display: inline-block; font-weight: bold;">Share Your Experience</a>
                    </div>
                    
                    <p>Thank you for being an amazing customer!</p>
                    
                    <p>Best,<br>{{business_owner}}</p>
                    
                    <p style="color: #666; font-size: 14px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
                        {{business_name}}<br>
                        {{business_phone}}<br>
                        {{business_website}}
                    </p>
                </body>
                </html>
                """;

            case "winback_initial" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>We miss you!</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #4F46E5;">Hi {{customer_name}},</h2>
                    
                    <p>It's been a while since we last worked together! We hope everything has been going well.</p>
                    
                    <p>We'd love to help you again with any {{business_industry}} needs you might have.</p>
                    
                    <p><strong>As a valued past customer, we're offering 10% off your next service!</strong></p>
                    
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="{{business_website}}" style="background: #4F46E5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; display: inline-block; font-weight: bold;">Book Now</a>
                    </div>
                    
                    <p>Looking forward to working with you again!</p>
                    
                    <p>Best,<br>The {{business_name}} Team</p>
                    
                    <p style="color: #666; font-size: 14px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
                        {{business_name}}<br>
                        {{business_phone}}<br>
                        {{business_website}}
                    </p>
                </body>
                </html>
                """;

            case "winback_social_proof" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>See what our customers are saying...</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #4F46E5;">Hi {{customer_name}},</h2>
                    
                    <p>We wanted to share some recent success stories from customers just like you:</p>
                    
                    <div style="background: #F3F4F6; padding: 20px; border-radius: 8px; margin: 20px 0;">
                        <p style="font-style: italic; color: #555;">"{{business_name}} did an amazing job! Highly recommend!"</p>
                        <p style="text-align: right; color: #888; font-size: 14px;">- Recent Customer</p>
                    </div>
                    
                    <p>We're here whenever you need us. <strong>Your 10% discount is still available!</strong></p>
                    
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="{{business_website}}" style="background: #4F46E5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; display: inline-block; font-weight: bold;">Get Started</a>
                    </div>
                    
                    <p>Best,<br>The {{business_name}} Team</p>
                    
                    <p style="color: #666; font-size: 14px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
                        {{business_name}}<br>
                        {{business_phone}}<br>
                        {{business_website}}
                    </p>
                </body>
                </html>
                """;

            case "referral_thankyou" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Thank you for your amazing review!</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333; max-width: 600px; margin: 0 auto; padding: 20px;">
                    <h2 style="color: #4F46E5;">Hi {{customer_name}},</h2>
                    
                    <p>Thank you so much for your wonderful review! It truly means the world to us. ⭐⭐⭐⭐⭐</p>
                    
                    <p>Since you had such a great experience, would you mind referring {{business_name}} to friends or family who might need our services?</p>
                    
                    <p><strong>For every referral that books with us, you'll get $25 off your next service!</strong></p>
                    
                    <div style="text-align: center; margin: 30px 0;">
                        <a href="{{referral_link}}" style="background: #4F46E5; color: white; padding: 12px 24px; text-decoration: none; border-radius: 8px; display: inline-block; font-weight: bold;">Refer a Friend</a>
                    </div>
                    
                    <p>Thanks for being awesome!</p>
                    
                    <p>Best,<br>{{business_owner}}<br>{{business_name}}</p>
                    
                    <p style="color: #666; font-size: 14px; margin-top: 30px; padding-top: 20px; border-top: 1px solid #eee;">
                        {{business_name}}<br>
                        {{business_phone}}<br>
                        {{business_website}}
                    </p>
                </body>
                </html>
                """;

            default -> "<p>Hi {{customer_name}}, thank you for choosing {{business_name}}!</p>";
        };
    }
}