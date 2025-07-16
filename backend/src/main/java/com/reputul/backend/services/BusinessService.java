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

    public void updateReputationScore(Business business) {
        double score = reputationService.getReputationScore(business.getId());
        business.setReputationScore(score);
        businessRepo.save(business);
    }
}
