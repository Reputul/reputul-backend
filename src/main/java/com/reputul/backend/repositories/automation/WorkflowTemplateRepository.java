package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.WorkflowTemplate;
import com.reputul.backend.models.automation.AutomationWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Long> {

    /**
     * Find active templates
     */
    List<WorkflowTemplate> findByIsActiveTrueOrderByCreatedAtDesc();

    /**
     * Find templates by category
     */
    List<WorkflowTemplate> findByCategoryAndIsActiveTrueOrderByCreatedAtDesc(String category);

    /**
     * Find templates by trigger type
     */
    List<WorkflowTemplate> findByTriggerTypeAndIsActiveTrueOrderByCreatedAtDesc(
            AutomationWorkflow.TriggerType triggerType);
}