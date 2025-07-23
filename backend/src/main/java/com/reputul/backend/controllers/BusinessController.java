package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessRepository businessRepo;
    private final UserRepository userRepo;

    public BusinessController(BusinessRepository businessRepo, UserRepository userRepo) {
        this.businessRepo = businessRepo;
        this.userRepo = userRepo;
    }

    @GetMapping
    public List<Business> getAll() {
        return businessRepo.findAll();
    }

    @PostMapping
    public Business createBusiness(@RequestBody Business business,
                                   @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User owner = userRepo.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found with email: " + email));

        business.setOwner(owner);
        business.setCreatedAt(LocalDateTime.now());
        return businessRepo.save(business);
    }

    @GetMapping("/user/{userId}")
    public List<Business> getByUser(@PathVariable Long userId) {
        return businessRepo.findByOwnerId(userId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getBusinessById(@PathVariable Long id) {
        return businessRepo.findById(id)
                .map(business -> {
                    BusinessResponseDto response = BusinessResponseDto.builder()
                            .id(business.getId())
                            .name(business.getName())
                            .industry(business.getIndustry())
                            .phone(business.getPhone())
                            .website(business.getWebsite())
                            .address(business.getAddress())
                            .reputationScore(business.getReputationScore())
                            .badge(business.getBadge())
                            .build();
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

}
