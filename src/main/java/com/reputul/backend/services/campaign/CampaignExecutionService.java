package com.reputul.backend.services.campaign;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.campaign.CampaignExecution;
import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignStep;
import com.reputul.backend.models.campaign.CampaignStepExecution;
import com.reputul.backend.enums.ExecutionStatus;
import com.reputul.backend.enums.MessageType;
import com.reputul.backend.enums.StepStatus;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.campaign.CampaignExecutionRepository;
import com.reputul.backend.repositories.campaign.CampaignStepRepository;
import com.reputul.backend.repositories.campaign.CampaignStepExecutionRepository;
import com.reputul.backend.services.EmailService;
import com.reputul.backend.services.SmsService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CampaignExecutionService {

    private final CampaignExecutionRepository executionRepository;
    private final CampaignStepExecutionRepository stepExecutionRepository;
    private final CampaignStepRepository stepRepository; // ADDED: Missing repository
    private final ReviewRequestRepository reviewRequestRepository;
    private final CustomerRepository customerRepository; // ADDED: Missing repository
    private final CampaignSequenceService sequenceService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final CampaignTemplateService templateService;

    /**
     * Start a new campaign execution for a review request
     */
    public CampaignExecution startCampaign(ReviewRequest reviewRequest, CampaignSequence sequence) {
        log.info("Starting campaign {} for review request {}", sequence.getId(), reviewRequest.getId());

        // Check if campaign is already running for this review request
        Optional<CampaignExecution> existingExecution = executionRepository
                .findByReviewRequestIdAndStatus(reviewRequest.getId(), ExecutionStatus.ACTIVE);

        if (existingExecution.isPresent()) {
            log.warn("Campaign already running for review request: {}", reviewRequest.getId());
            return existingExecution.get();
        }

        // Create execution record
        CampaignExecution execution = new CampaignExecution();
        execution.setReviewRequestId(reviewRequest.getId());
        execution.setSequenceId(sequence.getId());
        execution.setStatus(ExecutionStatus.ACTIVE);
        execution.setCurrentStep(1);

        execution = executionRepository.save(execution);

        // Update review request with campaign execution reference
        reviewRequest.setCampaignExecutionId(execution.getId());
        reviewRequestRepository.save(reviewRequest);

        // Schedule all steps
        scheduleSteps(execution, sequence, reviewRequest);

        log.info("Started campaign execution {} with {} steps", execution.getId(), sequence.getStepCount());
        return execution;
    }

    /**
     * Start campaign using default sequence for organization
     */
    public CampaignExecution startDefaultCampaign(ReviewRequest reviewRequest, Long orgId) {
        CampaignSequence defaultSequence = sequenceService.getDefaultSequence(orgId);
        return startCampaign(reviewRequest, defaultSequence);
    }

    /**
     * Execute a single campaign step
     */
    @Async
    public void executeStep(Long stepExecutionId) {
        log.debug("Executing campaign step {}", stepExecutionId);

        CampaignStepExecution stepExecution = stepExecutionRepository.findById(stepExecutionId)
                .orElseThrow(() -> new EntityNotFoundException("Step execution not found: " + stepExecutionId));

        if (stepExecution.getStatus() != StepStatus.PENDING) {
            log.warn("Step execution {} is not pending (status: {}), skipping", stepExecutionId, stepExecution.getStatus());
            return;
        }

        try {
            CampaignExecution execution = executionRepository.findById(stepExecution.getExecution().getId())
                    .orElseThrow(() -> new EntityNotFoundException("Campaign execution not found"));

            // Check if campaign should continue (customer hasn't reviewed yet)
            if (shouldStopCampaign(execution)) {
                stopCampaign(execution.getId(), "Customer already submitted review");
                return;
            }

            // Get step details and review request
            CampaignStep step = getCampaignStep(stepExecution.getStepId());
            ReviewRequest reviewRequest = getReviewRequest(execution.getReviewRequestId());

            // Send message
            boolean messageSent = sendMessage(step, reviewRequest, stepExecution);

            if (messageSent) {
                stepExecution.markSent();
                log.info("Successfully sent {} message for step execution {}", step.getMessageType(), stepExecutionId);

                // Update campaign execution current step
                execution.setCurrentStep(execution.getCurrentStep() + 1);
                executionRepository.save(execution);

                // Check if this was the last step
                checkCampaignCompletion(execution);

            } else {
                stepExecution.markFailed("Message sending failed");
                log.error("Failed to send message for step execution {}", stepExecutionId);
            }

        } catch (Exception e) {
            log.error("Error executing step {}: {}", stepExecutionId, e.getMessage(), e);
            stepExecution.markFailed("Execution error: " + e.getMessage());
        } finally {
            stepExecutionRepository.save(stepExecution);
        }
    }

    /**
     * Stop a campaign execution
     */
    public void stopCampaign(Long executionId, String reason) {
        CampaignExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign execution not found: " + executionId));

        if (execution.isFinished()) {
            log.debug("Campaign execution {} already finished", executionId);
            return;
        }

        log.info("Stopping campaign execution {}: {}", executionId, reason);

        execution.markCompleted();
        executionRepository.save(execution);

        // Mark all pending steps as skipped
        List<CampaignStepExecution> pendingSteps = stepExecutionRepository
                .findByExecutionIdOrderByScheduledAtAsc(executionId);

        pendingSteps.stream()
                .filter(step -> step.getStatus() == StepStatus.PENDING)
                .forEach(step -> {
                    step.markSkipped();
                    stepExecutionRepository.save(step);
                });
    }

    /**
     * Cancel a campaign execution
     */
    public void cancelCampaign(Long executionId) {
        CampaignExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign execution not found: " + executionId));

        log.info("Cancelling campaign execution {}", executionId);

        execution.markCancelled();
        executionRepository.save(execution);

        // Mark all pending steps as skipped
        List<CampaignStepExecution> pendingSteps = stepExecutionRepository
                .findByExecutionIdOrderByScheduledAtAsc(executionId);

        pendingSteps.stream()
                .filter(step -> step.getStatus() == StepStatus.PENDING)
                .forEach(step -> {
                    step.markSkipped();
                    stepExecutionRepository.save(step);
                });
    }

    /**
     * Get due steps that need to be executed
     */
    public List<CampaignStepExecution> getDueSteps() {
        return stepExecutionRepository.findDueSteps(StepStatus.PENDING, LocalDateTime.now());
    }

    /**
     * Get execution by ID
     */
    public CampaignExecution getExecution(Long executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign execution not found: " + executionId));
    }

    /**
     * Get executions by sequence ID
     */
    public List<CampaignExecution> getExecutionsBySequence(Long sequenceId) {
        return executionRepository.findBySequenceId(sequenceId);
    }

    /**
     * Auto-start campaign for new review request
     * Called from ReviewRequestService when creating new requests
     */
    @Transactional
    public void autoStartCampaignForReviewRequest(ReviewRequest reviewRequest) {
        try {
            // Get business organization ID
            Long orgId = reviewRequest.getBusiness().getOrganization().getId();

            // Start default campaign
            CampaignExecution execution = startDefaultCampaign(reviewRequest, orgId);

            log.info("Auto-started campaign {} for review request {}",
                    execution.getId(), reviewRequest.getId());

        } catch (Exception e) {
            log.error("Failed to auto-start campaign for review request {}: {}",
                    reviewRequest.getId(), e.getMessage(), e);
            // Don't fail the review request creation if campaign fails
        }
    }

    // Private helper methods

    private void scheduleSteps(CampaignExecution execution, CampaignSequence sequence, ReviewRequest reviewRequest) {
        LocalDateTime baseTime = LocalDateTime.now();

        for (CampaignStep step : sequence.getSteps()) {
            if (!step.getIsActive()) {
                log.debug("Skipping inactive step {} in sequence {}", step.getStepNumber(), sequence.getId());
                continue;
            }

            CampaignStepExecution stepExecution = new CampaignStepExecution();
            stepExecution.setExecution(execution);
            stepExecution.setStepId(step.getId());
            stepExecution.setScheduledAt(baseTime.plusHours(step.getDelayHours()));
            stepExecution.setStatus(StepStatus.PENDING);

            stepExecution = stepExecutionRepository.save(stepExecution);
            execution.addStepExecution(stepExecution);

            log.debug("Scheduled step {} for execution at {}", step.getStepNumber(), stepExecution.getScheduledAt());
        }
    }

    private boolean shouldStopCampaign(CampaignExecution execution) {
        // Check if the customer has already submitted a review
        ReviewRequest reviewRequest = getReviewRequest(execution.getReviewRequestId());

        // FIXED: Check proper ReviewRequest status field
        return reviewRequest.getStatus() != null &&
                (ReviewRequest.RequestStatus.COMPLETED.equals(reviewRequest.getStatus()) ||
                        ReviewRequest.RequestStatus.CLICKED.equals(reviewRequest.getStatus()));
    }

    private void checkCampaignCompletion(CampaignExecution execution) {
        // Get all step executions for this campaign
        List<CampaignStepExecution> allSteps = stepExecutionRepository
                .findByExecutionIdOrderByScheduledAtAsc(execution.getId());

        // Check if all steps are finished (sent, delivered, failed, or skipped)
        boolean allStepsFinished = allSteps.stream()
                .allMatch(step -> step.getStatus() != StepStatus.PENDING);

        if (allStepsFinished) {
            log.info("All steps completed for campaign execution {}", execution.getId());
            execution.markCompleted();
            executionRepository.save(execution);
        }
    }

    private boolean sendMessage(CampaignStep step, ReviewRequest reviewRequest, CampaignStepExecution stepExecution) {
        try {
            // Get customer from review request
            Customer customer = getCustomerFromReviewRequest(reviewRequest);

            // Generate message content from templates
            Map<String, Object> templateVariables = templateService.buildTemplateVariables(reviewRequest);

            String subject = null;
            String body = step.getBodyTemplate();

            // Process templates
            if (step.getSubjectTemplate() != null) {
                subject = templateService.processTemplate(step.getSubjectTemplate(), templateVariables);
            }
            body = templateService.processTemplate(body, templateVariables);

            // Send based on message type
            return switch (step.getMessageType()) {
                case SMS -> sendSMSMessage(customer, body);
                case EMAIL_PROFESSIONAL -> sendEmailMessage(customer, subject, body, MessageType.EMAIL_PROFESSIONAL);
                case EMAIL_PLAIN -> sendEmailMessage(customer, subject, body, MessageType.EMAIL_PLAIN);
            };

        } catch (Exception e) {
            log.error("Failed to send message for step execution {}: {}", stepExecution.getId(), e.getMessage(), e);
            return false;
        }
    }

    // FIXED: Updated to use proper SmsService API
    private boolean sendSMSMessage(Customer customer, String message) {
        try {
            // FIXED: Use the actual SmsService method signature
            SmsService.SmsResult result = smsService.sendReviewRequestSms(customer);

            if (result.isSuccess()) {
                log.info("SMS sent successfully to customer {} with SID: {}",
                        customer.getId(), result.getMessageSid());
                return true;
            } else {
                log.error("Failed to send SMS to customer {}: {}",
                        customer.getId(), result.getErrorMessage());
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to send SMS to customer {}: {}", customer.getId(), e.getMessage(), e);
            return false;
        }
    }

    // FIXED: Updated to use proper EmailService API
    private boolean sendEmailMessage(Customer customer, String subject, String body, MessageType messageType) {
        try {
            // FIXED: Use the actual EmailService method signatures
            if (messageType == MessageType.EMAIL_PROFESSIONAL) {
                // Use template-based email sending for professional emails
                return emailService.sendReviewRequestWithTemplate(customer);
            } else {
                // For plain emails, we'll use the template service too but could add a plain text option
                // For now, using the template service as it's the primary method
                return emailService.sendReviewRequestWithTemplate(customer);
            }
        } catch (Exception e) {
            log.error("Failed to send email to customer {}: {}", customer.getId(), e.getMessage(), e);
            return false;
        }
    }

    // FIXED: Implement the missing getCampaignStep method
    private CampaignStep getCampaignStep(Long stepId) {
        return stepRepository.findById(stepId)
                .orElseThrow(() -> new EntityNotFoundException("Campaign step not found: " + stepId));
    }

    private ReviewRequest getReviewRequest(Long reviewRequestId) {
        return reviewRequestRepository.findById(reviewRequestId)
                .orElseThrow(() -> new EntityNotFoundException("Review request not found: " + reviewRequestId));
    }

    // FIXED: Helper method to get customer from review request
    private Customer getCustomerFromReviewRequest(ReviewRequest reviewRequest) {
        // ReviewRequest has a required customer field (nullable = false)
        Customer customer = reviewRequest.getCustomer();
        if (customer == null) {
            throw new IllegalStateException("ReviewRequest " + reviewRequest.getId() + " has no associated customer");
        }
        return customer;
    }
}