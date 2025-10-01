package com.reputul.backend.controllers.campaign;

import com.reputul.backend.dto.campaign.CampaignSequenceDto;
import com.reputul.backend.dto.campaign.CampaignExecutionDto;
import com.reputul.backend.dto.campaign.CampaignStepDto;
import com.reputul.backend.dto.campaign.request.CreateSequenceRequest;
import com.reputul.backend.dto.campaign.request.UpdateSequenceRequest;
import com.reputul.backend.dto.campaign.request.StartCampaignRequest;
import com.reputul.backend.models.User;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignExecution;
import com.reputul.backend.models.campaign.CampaignStep;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.services.campaign.CampaignSequenceService;
import com.reputul.backend.services.campaign.CampaignExecutionService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campaigns")
@RequiredArgsConstructor
@Slf4j
public class CampaignController {

    private final CampaignSequenceService sequenceService;
    private final CampaignExecutionService executionService;
    private final UserRepository userRepository;
    private final ReviewRequestRepository reviewRequestRepository;

    // ================================
    // CAMPAIGN SEQUENCES
    // ================================

    @GetMapping("/sequences")
    public ResponseEntity<List<CampaignSequenceDto>> getSequences(Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<CampaignSequence> sequences = sequenceService.getActiveSequences(user.getOrganization().getId());

        List<CampaignSequenceDto> dtos = sequences.stream()
                .map(this::convertSequenceToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/sequences/{sequenceId}")
    public ResponseEntity<CampaignSequenceDto> getSequence(
            @PathVariable Long sequenceId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(sequenceId);

            // Ensure sequence belongs to user's organization
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(convertSequenceToDto(sequence));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/sequences")
    public ResponseEntity<CampaignSequenceDto> createSequence(
            @Valid @RequestBody CreateSequenceRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            CampaignSequence sequence = sequenceService.createSequence(
                    user.getOrganization().getId(), request);

            return ResponseEntity.ok(convertSequenceToDto(sequence));
        } catch (IllegalArgumentException e) {
            log.warn("Failed to create sequence for user {}: {}", user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/sequences/{sequenceId}")
    public ResponseEntity<CampaignSequenceDto> updateSequence(
            @PathVariable Long sequenceId,
            @Valid @RequestBody UpdateSequenceRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership before update
            CampaignSequence existingSequence = sequenceService.getSequenceWithSteps(sequenceId);
            if (!existingSequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            CampaignSequence updated = sequenceService.updateSequence(sequenceId, request);
            return ResponseEntity.ok(convertSequenceToDto(updated));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Failed to update sequence {} for user {}: {}", sequenceId, user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/sequences/{sequenceId}")
    public ResponseEntity<Void> deleteSequence(
            @PathVariable Long sequenceId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership before deletion
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(sequenceId);
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            sequenceService.deleteSequence(sequenceId);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Cannot delete sequence {} for user {}: {}", sequenceId, user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/sequences/default")
    public ResponseEntity<CampaignSequenceDto> getDefaultSequence(Authentication authentication) {
        User user = getCurrentUser(authentication);
        CampaignSequence defaultSequence = sequenceService.getDefaultSequence(user.getOrganization().getId());
        return ResponseEntity.ok(convertSequenceToDto(defaultSequence));
    }

    // NEW: Toggle sequence active/inactive status
    @PutMapping("/sequences/{sequenceId}/status")
    public ResponseEntity<CampaignSequenceDto> updateSequenceStatus(
            @PathVariable Long sequenceId,
            @RequestBody Map<String, Boolean> request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(sequenceId);
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            Boolean isActive = request.get("isActive");
            if (isActive == null) {
                return ResponseEntity.badRequest().build();
            }

            // Update the status
            CampaignSequence updated = sequenceService.updateSequenceStatus(sequenceId, isActive);

            log.info("Sequence {} {} by user {}",
                    sequenceId,
                    isActive ? "activated" : "deactivated",
                    user.getEmail());

            return ResponseEntity.ok(convertSequenceToDto(updated));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Cannot update status for sequence {} for user {}: {}",
                    sequenceId, user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // NEW: Set a sequence as the default
    @PutMapping("/sequences/{sequenceId}/set-default")
    public ResponseEntity<CampaignSequenceDto> setAsDefault(
            @PathVariable Long sequenceId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(sequenceId);
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            // Set as default (this will unset any other default sequences for the org)
            CampaignSequence updated = sequenceService.setAsDefault(
                    sequenceId,
                    user.getOrganization().getId());

            log.info("Sequence {} set as default for organization {} by user {}",
                    sequenceId,
                    user.getOrganization().getId(),
                    user.getEmail());

            return ResponseEntity.ok(convertSequenceToDto(updated));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Cannot set sequence {} as default for user {}: {}",
                    sequenceId, user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ================================
    // CAMPAIGN EXECUTIONS
    // ================================

    @GetMapping("/executions")
    public ResponseEntity<List<CampaignExecutionDto>> getExecutions(
            @RequestParam(required = false) Long sequenceId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        List<CampaignExecution> executions;
        if (sequenceId != null) {
            // Verify sequence ownership
            try {
                CampaignSequence sequence = sequenceService.getSequenceWithSteps(sequenceId);
                if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                    return ResponseEntity.notFound().build();
                }
                executions = executionService.getExecutionsBySequence(sequenceId);
            } catch (EntityNotFoundException e) {
                return ResponseEntity.notFound().build();
            }
        } else {
            // Get all active executions for the organization
            executions = executionService.getActiveExecutions();
            // Filter by organization (this would need a repository method)
            executions = executions.stream()
                    .filter(execution -> {
                        try {
                            CampaignSequence seq = sequenceService.getSequenceWithSteps(execution.getSequenceId());
                            return seq.getOrgId().equals(user.getOrganization().getId());
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
        }

        List<CampaignExecutionDto> dtos = executions.stream()
                .map(this::convertExecutionToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<CampaignExecutionDto> getExecution(
            @PathVariable Long executionId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            CampaignExecution execution = executionService.getExecution(executionId);

            // Verify ownership through sequence
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(execution.getSequenceId());
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(convertExecutionToDto(execution));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/executions")
    public ResponseEntity<CampaignExecutionDto> startCampaign(
            @Valid @RequestBody StartCampaignRequest request,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify review request ownership
            ReviewRequest reviewRequest = reviewRequestRepository.findById(request.getReviewRequestId())
                    .orElseThrow(() -> new EntityNotFoundException("Review request not found"));

            if (!reviewRequest.getBusiness().getUser().getId().equals(user.getId())) {
                return ResponseEntity.notFound().build();
            }

            CampaignExecution execution;
            if (request.getSequenceId() != null) {
                // Use specific sequence
                CampaignSequence sequence = sequenceService.getSequenceWithSteps(request.getSequenceId());
                if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                    return ResponseEntity.notFound().build();
                }
                execution = executionService.startCampaign(reviewRequest, sequence);
            } else {
                // Use default sequence
                execution = executionService.startDefaultCampaign(reviewRequest, user.getOrganization().getId());
            }

            return ResponseEntity.ok(convertExecutionToDto(execution));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Cannot start campaign for user {}: {}", user.getEmail(), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/executions/{executionId}/stop")
    public ResponseEntity<Void> stopExecution(
            @PathVariable Long executionId,
            @RequestParam(required = false, defaultValue = "Manual stop") String reason,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership
            CampaignExecution execution = executionService.getExecution(executionId);
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(execution.getSequenceId());
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            executionService.stopCampaign(executionId, reason);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/executions/{executionId}/cancel")
    public ResponseEntity<Void> cancelExecution(
            @PathVariable Long executionId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);

        try {
            // Verify ownership
            CampaignExecution execution = executionService.getExecution(executionId);
            CampaignSequence sequence = sequenceService.getSequenceWithSteps(execution.getSequenceId());
            if (!sequence.getOrgId().equals(user.getOrganization().getId())) {
                return ResponseEntity.notFound().build();
            }

            executionService.cancelCampaign(executionId);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> getCampaignAnalytics(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        try {
            String email = userDetails.getUsername();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // TODO: Implement actual analytics logic
            // For now, return dummy data
            Map<String, Object> analytics = new HashMap<>();
            analytics.put("totalExecutions", 0);
            analytics.put("activeExecutions", 0);
            analytics.put("completedExecutions", 0);
            analytics.put("failedExecutions", 0);
            analytics.put("completionRate", 0.0);
            analytics.put("averageStepsCompleted", 0.0);
            analytics.put("sequencePerformance", new ArrayList<>());

            return ResponseEntity.ok(analytics);
        } catch (Exception e) {
            log.error("Error getting campaign analytics: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    // ================================
    // HELPER METHODS
    // ================================

    private User getCurrentUser(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    private CampaignSequenceDto convertSequenceToDto(CampaignSequence sequence) {
        List<CampaignStepDto> stepDtos = null;
        if (sequence.getSteps() != null && !sequence.getSteps().isEmpty()) {
            stepDtos = sequence.getSteps().stream()
                    .map(this::convertStepToDto)
                    .collect(Collectors.toList());
        }

        return CampaignSequenceDto.builder()
                .id(sequence.getId())
                .orgId(sequence.getOrgId())
                .name(sequence.getName())
                .description(sequence.getDescription())
                .isDefault(sequence.getIsDefault())
                .isActive(sequence.getIsActive())
                .stepCount(sequence.getStepCount())
                .createdAt(sequence.getCreatedAt())
                .updatedAt(sequence.getUpdatedAt())
                .steps(stepDtos)
                .build();
    }

    private CampaignStepDto convertStepToDto(CampaignStep step) {
        return CampaignStepDto.builder()
                .id(step.getId())
                .sequenceId(step.getSequence().getId())
                .stepNumber(step.getStepNumber())
                .delayHours(step.getDelayHours())
                .messageType(step.getMessageType())
                .subjectTemplate(step.getSubjectTemplate())
                .bodyTemplate(step.getBodyTemplate())
                .isActive(step.getIsActive())
                .delayDescription(step.getDelayDescription())
                .createdAt(step.getCreatedAt())
                .build();
    }

    private CampaignExecutionDto convertExecutionToDto(CampaignExecution execution) {
        // Get additional info from review request
        String customerName = null;
        String customerEmail = null;
        String businessName = null;
        String sequenceName = null;

        try {
            ReviewRequest reviewRequest = reviewRequestRepository.findById(execution.getReviewRequestId())
                    .orElse(null);
            if (reviewRequest != null) {
                customerName = reviewRequest.getCustomer().getName();
                customerEmail = reviewRequest.getCustomer().getEmail();
                businessName = reviewRequest.getBusiness().getName();
            }

            CampaignSequence sequence = sequenceService.getSequenceWithSteps(execution.getSequenceId());
            sequenceName = sequence.getName();
        } catch (Exception e) {
            log.warn("Failed to get additional info for execution {}: {}", execution.getId(), e.getMessage());
        }

        return CampaignExecutionDto.builder()
                .id(execution.getId())
                .reviewRequestId(execution.getReviewRequestId())
                .sequenceId(execution.getSequenceId())
                .sequenceName(sequenceName)
                .currentStep(execution.getCurrentStep())
                .status(execution.getStatus())
                .startedAt(execution.getStartedAt())
                .completedAt(execution.getCompletedAt())
                .customerName(customerName)
                .customerEmail(customerEmail)
                .businessName(businessName)
                .build();
    }
}