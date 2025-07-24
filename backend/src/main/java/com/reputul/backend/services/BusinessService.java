package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.BusinessRepository;
import org.springframework.stereotype.Service;

@Service
public class BusinessService {

    private final BusinessRepository businessRepo;
    private final ReputationService reputationService;

    public BusinessService(BusinessRepository businessRepo, ReputationService reputationService) {
        this.businessRepo = businessRepo;
        this.reputationService = reputationService;
    }

    /**
     * Triggers a full recalculation of reputation score and badge for the given business.
     */
    public void updateReputationScore(Business business) {
        reputationService.updateBusinessReputationAndBadge(business.getId());
    }
}
