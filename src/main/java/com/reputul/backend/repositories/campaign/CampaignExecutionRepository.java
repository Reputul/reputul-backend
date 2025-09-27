package com.reputul.backend.repositories.campaign;

import com.reputul.backend.models.campaign.CampaignExecution;
import com.reputul.backend.enums.ExecutionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignExecutionRepository extends JpaRepository<CampaignExecution, Long> {

    Optional<CampaignExecution> findByReviewRequestIdAndStatus(Long reviewRequestId, ExecutionStatus status);

    List<CampaignExecution> findByStatus(ExecutionStatus status);

    List<CampaignExecution> findBySequenceId(Long sequenceId);

    @Query("SELECT ce FROM CampaignExecution ce WHERE ce.status = :status AND ce.startedAt < :before")
    List<CampaignExecution> findByStatusAndStartedAtBefore(
            @Param("status") ExecutionStatus status,
            @Param("before") LocalDateTime before
    );

    long countBySequenceIdAndStatus(Long sequenceId, ExecutionStatus status);

    @Query("SELECT COUNT(ce) FROM CampaignExecution ce WHERE ce.sequenceId IN " +
            "(SELECT cs.id FROM CampaignSequence cs WHERE cs.orgId = :orgId)")
    long countByOrgId(@Param("orgId") Long orgId);
}
