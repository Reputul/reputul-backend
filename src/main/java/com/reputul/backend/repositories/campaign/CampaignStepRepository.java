package com.reputul.backend.repositories.campaign;

import com.reputul.backend.models.campaign.CampaignStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignStepRepository extends JpaRepository<CampaignStep, Long> {

    List<CampaignStep> findBySequenceIdOrderByStepNumberAsc(Long sequenceId);

    List<CampaignStep> findBySequenceIdAndIsActiveTrueOrderByStepNumberAsc(Long sequenceId);

    void deleteBySequenceId(Long sequenceId);
}