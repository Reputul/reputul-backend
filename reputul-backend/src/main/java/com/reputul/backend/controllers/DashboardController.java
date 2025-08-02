package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final BusinessRepository businessRepo;
    private final UserRepository userRepo;

    public DashboardController(BusinessRepository businessRepo, UserRepository userRepo) {
        this.businessRepo = businessRepo;
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<BusinessResponseDto> getUserBusinesses(@AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User user = userRepo.findByEmail(email).orElseThrow(); // fetch user by email

        List<Business> businesses = businessRepo.findByUserId(user.getId()); // use existing method

        return businesses.stream().map(b -> BusinessResponseDto.builder()
                .id(b.getId())
                .name(b.getName())
                .industry(b.getIndustry())
                .phone(b.getPhone())
                .website(b.getWebsite())
                .address(b.getAddress())
                .reputationScore(b.getReputationScore())
                .badge(b.getBadge())
                .reviewCount(b.getReviews() != null ? b.getReviews().size() : 0)
                .reviewPlatformsConfigured(b.getReviewPlatformsConfigured()) // ADD THIS LINE!
                .build()
        ).toList();
    }
}