package com.reputul.backend.repositories.campaign;

import com.reputul.backend.models.campaign.CampaignStepExecution;
import com.reputul.backend.enums.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CampaignStepExecutionRepository extends JpaRepository<CampaignStepExecution, Long> {

    List<CampaignStepExecution> findByExecutionIdOrderByScheduledAtAsc(Long executionId);

    @Query("SELECT cse FROM CampaignStepExecution cse WHERE cse.status = :status AND cse.scheduledAt <= :now")
    List<CampaignStepExecution> findDueSteps(@Param("status") StepStatus status, @Param("now") LocalDateTime now);

    List<CampaignStepExecution> findByStatusAndScheduledAtBefore(StepStatus status, LocalDateTime scheduledAt);

    long countByExecutionIdAndStatus(Long executionId, StepStatus status);

    @Query("SELECT COUNT(cse) FROM CampaignStepExecution cse " +
            "WHERE cse.execution.id IN (SELECT ce.id FROM CampaignExecution ce WHERE ce.sequenceId = :sequenceId) " +
            "AND cse.status = :status")
    long countBySequenceIdAndStatus(@Param("sequenceId") Long sequenceId, @Param("status") StepStatus status);
}
