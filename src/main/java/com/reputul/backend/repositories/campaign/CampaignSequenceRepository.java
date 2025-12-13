package com.reputul.backend.repositories.campaign;

import com.reputul.backend.models.campaign.CampaignSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampaignSequenceRepository extends JpaRepository<CampaignSequence, Long> {

    List<CampaignSequence> findByOrgIdAndIsActiveTrue(Long orgId);

    List<CampaignSequence> findByOrgIdAndIsDefaultTrue(Long orgId);

    List<CampaignSequence> findByOrgIdOrderByCreatedAtDesc(Long orgId);

    @Query("SELECT cs FROM CampaignSequence cs LEFT JOIN FETCH cs.steps WHERE cs.id = :id")
    Optional<CampaignSequence> findByIdWithSteps(@Param("id") Long id);

    @Query("""
    SELECT DISTINCT cs
    FROM CampaignSequence cs
    LEFT JOIN FETCH cs.steps
    WHERE cs.orgId = :orgId
""")
    List<CampaignSequence> findByOrgIdWithSteps(@Param("orgId") Long orgId);

    boolean existsByOrgIdAndName(Long orgId, String name);

    long countByOrgId(Long orgId);
}