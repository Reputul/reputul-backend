package com.reputul.backend.controllers;

import com.reputul.backend.services.ReputationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reputation")
public class ReputationController {

    private final ReputationService reputationService;

    public ReputationController(ReputationService reputationService) {
        this.reputationService = reputationService;
    }

    @GetMapping("/business/{businessId}")
    public double getScore(@PathVariable Long businessId) {
        return reputationService.getReputationScore(businessId);
    }
}
