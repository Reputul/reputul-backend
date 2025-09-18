package com.reputul.backend.services;

import com.reputul.backend.models.User;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.automation.WorkflowTemplate;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.repositories.automation.WorkflowTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AutomationWorkflowTemplateService
 *
 * Creates and manages universal automation workflow templates for MVP
 * Provides 3 core templates that work for any service business
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutomationWorkflowTemplateService {

    private final WorkflowTemplateRepository templateRepository;
    private final AutomationWorkflowRepository workflowRepository;
    private final AutomationService automationService;

    /**
     * Create default automation workflow templates for new users
     * Called when user signs up or when they first access automation
     */
    @Transactional
    public void createDefaultWorkflowTemplatesForUser(User user) {
        log.info("Creating default automation workflow templates for user {}", user.getId());

        try {
            // Check if user already has workflow templates
            List<AutomationWorkflow> existingWorkflows = workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());
            if (!existingWorkflows.isEmpty()) {
                log.info("User {} already has {} workflows, skipping template creation", user.getId(), existingWorkflows.size());
                return;
            }

            // Create the 3 universal workflow templates
            createQuickReviewRequestTemplate(user);
            createFollowUpSequenceTemplate(user);
            createThankYouWorkflowTemplate(user);

            log.info("Successfully created 3 default workflow templates for user {}", user.getId());

        } catch (Exception e) {
            log.error("Error creating default workflow templates for user {}: {}", user.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to create default workflow templates: " + e.getMessage());
        }
    }

    /**
     * Template 1: Quick Review Request (24 hours after service completion)
     */
    private void createQuickReviewRequestTemplate(User user) {
        Map<String, Object> triggerConfig = Map.of(
                "delay_hours", 24,
                "business_hours_only", true,
                "max_executions_per_customer", 1
        );

        Map<String, Object> actions = Map.of(
                "send_review_request", Map.of(
                        "delivery_method", "EMAIL",
                        "template_type", "INITIAL_REQUEST",
                        "enabled", true
                ),
                "conditions", Map.of(
                        "has_email", true
                )
        );

        AutomationService.CreateWorkflowRequest request = AutomationService.CreateWorkflowRequest.builder()
                .name("24-Hour Review Request")
                .description("Sends a review request 24 hours after service completion during business hours")
                .triggerType(AutomationWorkflow.TriggerType.CUSTOMER_CREATED)
                .triggerConfig(triggerConfig)
                .actions(actions)
                .build();

        automationService.createWorkflow(user, request);
        log.info("Created '24-Hour Review Request' workflow for user {}", user.getId());
    }

    /**
     * Template 2: Follow-up Sequence (initial request + 7-day follow-up)
     */
    private void createFollowUpSequenceTemplate(User user) {
        Map<String, Object> triggerConfig = Map.of(
                "delay_hours", 24,
                "business_hours_only", true
        );

        Map<String, Object> actions = Map.of(
                "initial_request", Map.of(
                        "send_review_request", Map.of(
                                "delivery_method", "EMAIL",
                                "template_type", "INITIAL_REQUEST"
                        ),
                        "delay_hours", 0,
                        "enabled", true
                ),
                "follow_up", Map.of(
                        "send_email", Map.of(
                                "template_type", "FOLLOW_UP_7_DAY"
                        ),
                        "delay_days", 7,
                        "conditions", Map.of(
                                "no_review_submitted", true
                        ),
                        "enabled", true
                )
        );

        AutomationService.CreateWorkflowRequest request = AutomationService.CreateWorkflowRequest.builder()
                .name("Follow-up Sequence")
                .description("Initial review request + 7-day follow-up if no review received")
                .triggerType(AutomationWorkflow.TriggerType.CUSTOMER_CREATED)
                .triggerConfig(triggerConfig)
                .actions(actions)
                .build();

        automationService.createWorkflow(user, request);
        log.info("Created 'Follow-up Sequence' workflow for user {}", user.getId());
    }

    /**
     * Template 3: Thank You After Review
     */
    private void createThankYouWorkflowTemplate(User user) {
        Map<String, Object> triggerConfig = Map.of(
                "delay_minutes", 30,
                "business_hours_only", false
        );

        Map<String, Object> conditions = Map.of(
                "min_rating", 4,
                "has_email", true
        );

        Map<String, Object> actions = Map.of(
                "send_thank_you", Map.of(
                        "send_email", Map.of(
                                "template_type", "THANK_YOU"
                        ),
                        "enabled", true
                ),
                "conditions", conditions
        );

        AutomationService.CreateWorkflowRequest request = AutomationService.CreateWorkflowRequest.builder()
                .name("Thank You for Good Reviews")
                .description("Sends thank you email 30 minutes after receiving 4+ star review")
                .triggerType(AutomationWorkflow.TriggerType.REVIEW_COMPLETED)
                .triggerConfig(triggerConfig)
                .actions(actions)
                .build();

        automationService.createWorkflow(user, request);
        log.info("Created 'Thank You for Good Reviews' workflow for user {}", user.getId());
    }

    /**
     * Get recommended workflow templates for user
     */
    public Map<String, Object> getRecommendedTemplates(User user) {
        Map<String, Object> recommendations = new HashMap<>();

        try {
            // Count existing workflows
            List<AutomationWorkflow> existingWorkflows = workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());

            recommendations.put("hasExistingWorkflows", !existingWorkflows.isEmpty());
            recommendations.put("existingWorkflowCount", existingWorkflows.size());

            // Recommend templates based on what they don't have
            if (existingWorkflows.isEmpty()) {
                recommendations.put("recommended", List.of(
                        Map.of(
                                "name", "24-Hour Review Request",
                                "description", "Simple review request sent 24 hours after service completion",
                                "difficulty", "Beginner",
                                "estimatedSetupTime", "2 minutes"
                        ),
                        Map.of(
                                "name", "Follow-up Sequence",
                                "description", "Initial request + follow-up for better response rates",
                                "difficulty", "Intermediate",
                                "estimatedSetupTime", "5 minutes"
                        ),
                        Map.of(
                                "name", "Thank You Workflow",
                                "description", "Automatic thank you for positive reviews",
                                "difficulty", "Beginner",
                                "estimatedSetupTime", "3 minutes"
                        )
                ));
            } else {
                recommendations.put("recommended", List.of());
                recommendations.put("message", "You already have workflows configured");
            }

            recommendations.put("canCreateDefaults", existingWorkflows.isEmpty());

        } catch (Exception e) {
            log.error("Error getting recommended templates for user {}: {}", user.getId(), e.getMessage());
            recommendations.put("error", e.getMessage());
        }

        return recommendations;
    }

    /**
     * Create all default workflows at once
     */
    @Transactional
    public Map<String, Object> createAllDefaultWorkflows(User user) {
        Map<String, Object> result = new HashMap<>();

        try {
            createDefaultWorkflowTemplatesForUser(user);

            List<AutomationWorkflow> createdWorkflows = workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());

            result.put("success", true);
            result.put("workflowsCreated", createdWorkflows.size());
            result.put("message", "Created " + createdWorkflows.size() + " default automation workflows");
            result.put("workflows", createdWorkflows.stream()
                    .map(w -> Map.of(
                            "id", w.getId(),
                            "name", w.getName(),
                            "description", w.getDescription(),
                            "triggerType", w.getTriggerType().toString(),
                            "isActive", w.getIsActive()
                    )).toList());

        } catch (Exception e) {
            log.error("Error creating all default workflows for user {}: {}", user.getId(), e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Get template statistics and usage
     */
    public Map<String, Object> getTemplateStats(User user) {
        Map<String, Object> stats = new HashMap<>();

        try {
            List<AutomationWorkflow> workflows = workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(user.getOrganization());

            stats.put("totalWorkflows", workflows.size());
            stats.put("activeWorkflows", workflows.stream().mapToInt(w -> w.getIsActive() ? 1 : 0).sum());

            // Group by trigger type
            Map<String, Long> byTriggerType = workflows.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            w -> w.getTriggerType().toString(),
                            java.util.stream.Collectors.counting()));
            stats.put("workflowsByTrigger", byTriggerType);

            // Execution stats (if available)
            stats.put("totalExecutions", workflows.stream()
                    .mapToInt(w -> w.getExecutionCount() != null ? w.getExecutionCount() : 0)
                    .sum());

            stats.put("hasDefaultTemplates", workflows.stream()
                    .anyMatch(w -> List.of("24-Hour Review Request", "Follow-up Sequence", "Thank You for Good Reviews")
                            .contains(w.getName())));

        } catch (Exception e) {
            log.error("Error getting template stats for user {}: {}", user.getId(), e.getMessage());
            stats.put("error", e.getMessage());
        }

        return stats;
    }
}