package com.reputul.backend.services.campaign;

import com.reputul.backend.dto.campaign.request.CreateSequenceRequest;
import com.reputul.backend.dto.campaign.request.CreateStepRequest;
import com.reputul.backend.dto.campaign.request.UpdateSequenceRequest;
import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignStep;
import com.reputul.backend.enums.MessageType;
import com.reputul.backend.repositories.campaign.CampaignSequenceRepository;
import com.reputul.backend.repositories.campaign.CampaignStepRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CampaignSequenceService {

    private final CampaignSequenceRepository sequenceRepository;
    private final CampaignStepRepository stepRepository;

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

        // No default found - create one automatically
        log.info("No default campaign sequence found for org {}. Creating default sequence.", orgId);
        return createDefaultSequence(orgId);
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
        } else if (request.getIsDefault() != null && !request.getIsDefault()) {
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
        CampaignSequence sequence = sequenceRepository.findById(sequenceId)
                .orElseThrow(() -> new EntityNotFoundException("Sequence not found"));

        sequence.setIsActive(isActive);
        sequence.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        return sequenceRepository.save(sequence);
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

    // Private helper methods

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

        if (stepRequest.getBodyTemplate() == null || stepRequest.getBodyTemplate().trim().isEmpty()) {
            throw new IllegalArgumentException("Body template is required");
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
        step.setBodyTemplate(stepRequest.getBodyTemplate());
        step.setIsActive(true);

        return stepRepository.save(step);
    }

    private CampaignSequence createDefaultSequence(Long orgId) {
        CreateSequenceRequest request = new CreateSequenceRequest();
        request.setName("Default Review Collection");
        request.setDescription("Proven 1 SMS + 3 email sequence for maximum review collection");
        request.setIsDefault(true);

        List<CreateStepRequest> steps = List.of(
                // Step 1: Immediate SMS
                createStepRequest(1, 0, MessageType.SMS, null,
                        "Hi {{customerName}}! How was your experience with {{businessName}}? We'd love to hear about it: {{reviewLink}}"),

                // Step 2: Professional email after 24 hours
                createStepRequest(2, 24, MessageType.EMAIL_PROFESSIONAL,
                        "How was your {{serviceType}} experience?",
                        getDefaultEmailTemplate("professional")),

                // Step 3: Personal follow-up after 5 days
                createStepRequest(3, 120, MessageType.EMAIL_PLAIN,
                        "Quick favor?",
                        getDefaultEmailTemplate("plain")),

                // Step 4: Final reminder after 14 days
                createStepRequest(4, 336, MessageType.EMAIL_PLAIN,
                        "Last chance to share your experience",
                        getDefaultEmailTemplate("final"))
        );

        request.setSteps(steps);
        return createSequence(orgId, request);
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

    private String getDefaultEmailTemplate(String type) {
        return switch (type) {
            case "professional" -> """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>Share Your Experience</title>
                </head>
                <body style="font-family: Arial, sans-serif; line-height: 1.6; color: #333;">
                    <div style="max-width: 600px; margin: 0 auto; padding: 20px;">
                        <h2 style="color: #2c5aa0;">Thank you for choosing {{businessName}}!</h2>
                        
                        <p>Hi {{customerName}},</p>
                        
                        <p>We hope you're completely satisfied with your recent {{serviceType}} service. Your experience matters to us, and we'd love to hear about it!</p>
                        
                        <div style="text-align: center; margin: 30px 0;">
                            <a href="{{reviewLink}}" style="background-color: #2c5aa0; color: white; padding: 15px 30px; text-decoration: none; border-radius: 5px; display: inline-block; font-weight: bold;">Share Your Experience</a>
                        </div>
                        
                        <p>It only takes 30 seconds and helps other customers find great service providers like us.</p>
                        
                        <p>Thank you for your business!</p>
                        
                        <p>Best regards,<br>The {{businessName}} Team</p>
                        
                        <hr style="margin-top: 30px; border: none; border-top: 1px solid #eee;">
                        <p style="font-size: 12px; color: #666;">
                            {{businessName}}<br>
                            {{businessPhone}}<br>
                            {{businessWebsite}}
                        </p>
                    </div>
                </body>
                </html>
                """;

            case "plain" -> """
                Hi {{customerName}},
                
                I hope you were happy with your recent {{serviceType}} service from {{businessName}}.
                
                Would you mind taking 30 seconds to share your experience? It would mean the world to us:
                {{reviewLink}}
                
                Thanks so much!
                
                Best,
                {{businessName}}
                {{businessPhone}}
                """;

            case "final" -> """
                Hi {{customerName}},
                
                This is my last email about your recent {{serviceType}} service.
                
                If you were satisfied with our work, I'd be incredibly grateful if you could leave a quick review:
                {{reviewLink}}
                
                If there were any issues with our service, please reply to this email and I'll make it right.
                
                Thank you,
                {{businessName}}
                """;

            default -> "Thank you for choosing {{businessName}}! Please share your experience: {{reviewLink}}";
        };
    }
}