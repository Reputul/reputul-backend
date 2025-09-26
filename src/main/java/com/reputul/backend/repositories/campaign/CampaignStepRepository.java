package com.reputul.backend.repositories.campaign;

import com.reputul.backend.models.campaign.CampaignStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignStepRepository extends JpaRepository<CampaignStep, Long> {

    /**
     * Find all steps for a specific sequence, ordered by step number
     */
    @Query("SELECT cs FROM CampaignStep cs WHERE cs.sequence.id = :sequenceId ORDER BY cs.stepNumber ASC")
    List<CampaignStep> findBySequenceIdOrderByStepNumberAsc(@Param("sequenceId") Long sequenceId);

    /**
     * Find active steps for a specific sequence
     */
    @Query("SELECT cs FROM CampaignStep cs WHERE cs.sequence.id = :sequenceId AND cs.isActive = true ORDER BY cs.stepNumber ASC")
    List<CampaignStep> findActiveStepsBySequenceId(@Param("sequenceId") Long sequenceId);

    /**
     * Find step by sequence and step number
     */
    @Query("SELECT cs FROM CampaignStep cs WHERE cs.sequence.id = :sequenceId AND cs.stepNumber = :stepNumber")
    Optional<CampaignStep> findBySequenceIdAndStepNumber(@Param("sequenceId") Long sequenceId, @Param("stepNumber") Integer stepNumber);

    /**
     * Find steps by organization (through sequence)
     */
    @Query("SELECT cs FROM CampaignStep cs WHERE cs.sequence.orgId = :organizationId")
    List<CampaignStep> findByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * Count active steps in a sequence
     */
    @Query("SELECT COUNT(cs) FROM CampaignStep cs WHERE cs.sequence.id = :sequenceId AND cs.isActive = true")
    Long countActiveStepsBySequenceId(@Param("sequenceId") Long sequenceId);
}