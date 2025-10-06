package com.reputul.backend.controllers;

import com.reputul.backend.dto.WaitlistRequest;
import com.reputul.backend.dto.WaitlistResponse;
import com.reputul.backend.services.ConvertKitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Random;

@RestController
@RequestMapping("/api/v1/waitlist")
@RequiredArgsConstructor
@Slf4j
public class WaitlistController {

    private final ConvertKitService convertKitService;
    private final Random random = new Random();

    @PostMapping
    public ResponseEntity<WaitlistResponse> joinWaitlist(@Valid @RequestBody WaitlistRequest request) {
        log.info("Waitlist signup attempt for email: {}", request.getEmail());

        try {
            // Add to ConvertKit
            ConvertKitService.ConvertKitResponse ckResponse = convertKitService.addSubscriber(request.getEmail());

            if (ckResponse.isSuccess()) {
                WaitlistResponse response = WaitlistResponse.builder()
                        .success(true)
                        .message(ckResponse.getMessage())
                        .waitlistCount(getRealisticWaitlistCount())
                        .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                        .build();

                log.info("Successfully added {} to waitlist", request.getEmail());
                return ResponseEntity.ok(response);

            } else if (ckResponse.isDuplicate()) {
                WaitlistResponse response = WaitlistResponse.builder()
                        .success(false)
                        .message(ckResponse.getMessage())
                        .duplicate(true)
                        .waitlistCount(getRealisticWaitlistCount())
                        .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                        .build();

                log.info("Duplicate email attempt: {}", request.getEmail());
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);

            } else {
                WaitlistResponse response = WaitlistResponse.builder()
                        .success(false)
                        .message(ckResponse.getMessage())
                        .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                        .build();

                log.error("Failed to add {} to ConvertKit: {}", request.getEmail(), ckResponse.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }

        } catch (Exception e) {
            log.error("Unexpected error during waitlist signup for {}", request.getEmail(), e);

            WaitlistResponse response = WaitlistResponse.builder()
                    .success(false)
                    .message("Something went wrong. Please try again later.")
                    .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/count")
    public ResponseEntity<WaitlistCountResponse> getWaitlistCount() {
        WaitlistCountResponse response = WaitlistCountResponse.builder()
                .count(getRealisticWaitlistCount())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Generate a realistic, slowly growing waitlist count
     * This simulates organic growth patterns
     */
    private int getRealisticWaitlistCount() {
        // Base count from your current landing page
        int baseCount = 2847;

        // Calculate days since launch (adjust this date to your actual launch)
        OffsetDateTime launchDate = OffsetDateTime.of(2024, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC); // Adjust this
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long daysSinceLaunch = java.time.Duration.between(launchDate, now).toDays();

        // Realistic growth: 3-8 signups per day with some variation
        double avgSignupsPerDay = 5.5;
        double variation = random.nextGaussian() * 1.2; // Some randomness

        // Weekend and evening effects (slightly higher)
        int dayOfWeek = now.getDayOfWeek().getValue();
        int hour = now.getHour();

        double dayOfWeekMultiplier = (dayOfWeek >= 6) ? 1.2 : 1.0; // Weekends slightly higher
        double hourMultiplier = (hour >= 18 || hour <= 8) ? 1.15 : 1.0; // Evenings/early morning

        // Calculate growth
        double totalGrowth = daysSinceLaunch * avgSignupsPerDay * dayOfWeekMultiplier * hourMultiplier;
        totalGrowth += variation;

        // Add some intra-day variation (grows slightly throughout the day)
        double intraDay = (hour / 24.0) * avgSignupsPerDay * 0.3;

        int currentCount = baseCount + (int) Math.max(0, totalGrowth + intraDay);

        // Ensure it's always growing (minimum 1-2 per day)
        int minimumGrowth = (int) (daysSinceLaunch * 1.5);
        currentCount = Math.max(currentCount, baseCount + minimumGrowth);

        return currentCount;
    }

    // Response DTO for count endpoint
    @lombok.Data
    @lombok.Builder
    public static class WaitlistCountResponse {
        private int count;
        private OffsetDateTime timestamp;
    }
}