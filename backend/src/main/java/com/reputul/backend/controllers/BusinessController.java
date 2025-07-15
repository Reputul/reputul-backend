package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
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
    public Business createBusiness(@RequestBody Business business) {
        // Attach to user (hardcoded for now — replace with real user later)
        User owner = userRepo.findById(1L).orElseThrow(); // TEMP
        business.setOwner(owner);
        business.setCreatedAt(LocalDateTime.now());
        return businessRepo.save(business);
    }

    @GetMapping("/user/{userId}")
    public List<Business> getByUser(@PathVariable Long userId) {
        return businessRepo.findByOwnerId(userId);
    }
}
