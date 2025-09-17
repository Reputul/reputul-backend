package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.AutomationWorkflowStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationWorkflowStepRepository extends JpaRepository<AutomationWorkflowStep, Long> {

    List<AutomationWorkflowStep> findByWorkflowIdOrderByStepOrder(Long workflowId);

    void deleteByWorkflowId(Long workflowId);
}