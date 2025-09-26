package com.reputul.backend.services.campaign;

import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignStep;
import com.reputul.backend.enums.MessageType;
import com.reputul.backend.repositories.campaign.CampaignSequenceRepository;
import com.reputul.backend.repositories.campaign.CampaignStepRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        sequence.setIsDefault(request.getIsDefault());
        sequence.setIsActive(true);

        // If this is set as default, unset other defaults
        if (request.getIsDefault()) {
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
        Optional<CampaignSequence> defaultSequence = sequenceRepository.findByOrgIdAndIsDefaultTrue(orgId);

        if (defaultSequence.isPresent()) {
            return defaultSequence.get();
        }

        // Create default NiceJob-style sequence
        log.info("No default sequence found for org {}, creating one", orgId);
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
        sequenceRepository.findByOrgIdAndIsDefaultTrue(orgId)
                .ifPresent(defaultSequence -> {
                    defaultSequence.setIsDefault(false);
                    sequenceRepository.save(defaultSequence);
                });
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
        CreateSequenceRequest request = CreateSequenceRequest.builder()
                .name("Default Review Collection")
                .description("Proven 1 SMS + 3 email sequence for maximum review collection")
                .isDefault(true)
                .steps(List.of(
                        // Step 1: Immediate SMS
                        CreateStepRequest.builder()
                                .stepNumber(1)
                                .delayHours(0)
                                .messageType(MessageType.SMS)
                                .bodyTemplate("Hi {{customerName}}! How was your experience with {{businessName}}? We'd love to hear about it: {{reviewLink}}")
                                .build(),

                        // Step 2: Professional email after 24 hours
                        CreateStepRequest.builder()
                                .stepNumber(2)
                                .delayHours(24)
                                .messageType(MessageType.EMAIL_PROFESSIONAL)
                                .subjectTemplate("How was your {{serviceType}} experience?")
                                .bodyTemplate(getDefaultEmailTemplate("professional"))
                                .build(),

                        // Step 3: Personal follow-up after 5 days
                        CreateStepRequest.builder()
                                .stepNumber(3)
                                .delayHours(120) // 5 days
                                .messageType(MessageType.EMAIL_PLAIN)
                                .subjectTemplate("Quick favor?")
                                .bodyTemplate(getDefaultEmailTemplate("plain"))
                                .build(),

                        // Step 4: Final reminder after 14 days
                        CreateStepRequest.builder()
                                .stepNumber(4)
                                .delayHours(336) // 14 days
                                .messageType(MessageType.EMAIL_PLAIN)
                                .subjectTemplate("Last chance to share your experience")
                                .bodyTemplate(getDefaultEmailTemplate("final"))
                                .build()
                ))
                .build();

        return createSequence(orgId, request);
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

    // Request DTOs (you'll create these next)
    public static class CreateSequenceRequest {
        private String name;
        private String description;
        private Boolean isDefault;
        private List<CreateStepRequest> steps;

        // Builder pattern methods
        public static CreateSequenceRequestBuilder builder() {
            return new CreateSequenceRequestBuilder();
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Boolean getIsDefault() { return isDefault; }
        public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

        public List<CreateStepRequest> getSteps() { return steps; }
        public void setSteps(List<CreateStepRequest> steps) { this.steps = steps; }

        public static class CreateSequenceRequestBuilder {
            private CreateSequenceRequest request = new CreateSequenceRequest();

            public CreateSequenceRequestBuilder name(String name) {
                request.setName(name);
                return this;
            }

            public CreateSequenceRequestBuilder description(String description) {
                request.setDescription(description);
                return this;
            }

            public CreateSequenceRequestBuilder isDefault(Boolean isDefault) {
                request.setIsDefault(isDefault);
                return this;
            }

            public CreateSequenceRequestBuilder steps(List<CreateStepRequest> steps) {
                request.setSteps(steps);
                return this;
            }

            public CreateSequenceRequest build() {
                return request;
            }
        }
    }

    public static class CreateStepRequest {
        private Integer stepNumber;
        private Integer delayHours;
        private MessageType messageType;
        private String subjectTemplate;
        private String bodyTemplate;

        public static CreateStepRequestBuilder builder() {
            return new CreateStepRequestBuilder();
        }

        // Getters and setters
        public Integer getStepNumber() { return stepNumber; }
        public void setStepNumber(Integer stepNumber) { this.stepNumber = stepNumber; }

        public Integer getDelayHours() { return delayHours; }
        public void setDelayHours(Integer delayHours) { this.delayHours = delayHours; }

        public MessageType getMessageType() { return messageType; }
        public void setMessageType(MessageType messageType) { this.messageType = messageType; }

        public String getSubjectTemplate() { return subjectTemplate; }
        public void setSubjectTemplate(String subjectTemplate) { this.subjectTemplate = subjectTemplate; }

        public String getBodyTemplate() { return bodyTemplate; }
        public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

        public static class CreateStepRequestBuilder {
            private CreateStepRequest request = new CreateStepRequest();

            public CreateStepRequestBuilder stepNumber(Integer stepNumber) {
                request.setStepNumber(stepNumber);
                return this;
            }

            public CreateStepRequestBuilder delayHours(Integer delayHours) {
                request.setDelayHours(delayHours);
                return this;
            }

            public CreateStepRequestBuilder messageType(MessageType messageType) {
                request.setMessageType(messageType);
                return this;
            }

            public CreateStepRequestBuilder subjectTemplate(String subjectTemplate) {
                request.setSubjectTemplate(subjectTemplate);
                return this;
            }

            public CreateStepRequestBuilder bodyTemplate(String bodyTemplate) {
                request.setBodyTemplate(bodyTemplate);
                return this;
            }

            public CreateStepRequest build() {
                return request;
            }
        }
    }

    public static class UpdateSequenceRequest {
        private String name;
        private String description;
        private Boolean isDefault;
        private Boolean isActive;

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Boolean getIsDefault() { return isDefault; }
        public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    }
}