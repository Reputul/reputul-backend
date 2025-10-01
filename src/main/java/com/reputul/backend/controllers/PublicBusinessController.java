package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.BusinessRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * PUBLIC endpoint for business information
 * Used by SMS signup page and other public-facing features
 */
@RestController
@RequestMapping("/api/v1/public/businesses")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"https://reputul.com", "https://www.reputul.com", "http://localhost:3000"})
public class PublicBusinessController {

    private final BusinessRepository businessRepository;

    /**
     * PUBLIC: Get business info for SMS signup form
     * Allows the signup form to display business details without authentication
     */
    @GetMapping("/{businessId}")
    public ResponseEntity<?> getBusinessInfo(@PathVariable Long businessId) {
        try {
            Optional<Business> businessOpt = businessRepository.findById(businessId);

            if (businessOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Business business = businessOpt.get();

            // Return only public-safe business information
            return ResponseEntity.ok(Map.of(
                    "id", business.getId(),
                    "name", business.getName(),
                    "industry", business.getIndustry(),
                    "phone", business.getPhone() != null ? business.getPhone() : "",
                    "website", business.getWebsite() != null ? business.getWebsite() : "",
                    "address", business.getAddress() != null ? business.getAddress() : ""
            ));

        } catch (Exception e) {
            log.error("Error fetching public business info: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unable to fetch business information"));
        }
    }
}