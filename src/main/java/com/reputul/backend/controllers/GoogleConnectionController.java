//package com.reputul.backend.controllers;
//
//import com.reputul.backend.dto.GoogleConnectionDto;
//import com.reputul.backend.models.Business;
//import com.reputul.backend.models.User;
//import com.reputul.backend.services.GooglePlacesService;
//import com.reputul.backend.repositories.BusinessRepository;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.core.annotation.AuthenticationPrincipal;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.web.bind.annotation.*;
//
//import jakarta.validation.Valid;
//import java.util.Map;
//
//@RestController
//@RequestMapping("/api/v1/businesses")
//@RequiredArgsConstructor
//@Slf4j
//public class GoogleConnectionController {
//
//    private final GooglePlacesService googlePlacesService;
//    private final BusinessRepository businessRepository;
//
//    /**
//     * Connect Google Business Profile via manual URL
//     * POST /api/v1/businesses/{id}/google-connection
//     */
//    @PostMapping("/{id}/google-connection")
//    public ResponseEntity<?> connectGoogleUrl(
//            @PathVariable Long id,
//            @Valid @RequestBody GoogleConnectionDto dto,
//            @AuthenticationPrincipal UserDetails userDetails) {
//
//        try {
//            User user = (User) userDetails;
//
//            // Find business and verify ownership
//            Business business = businessRepository.findByIdAndOrganizationId(
//                            id, user.getOrganization().getId())
//                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
//
//            log.info("Connecting Google for business {} with URL: {}", id, dto.getGoogleMapsUrl());
//
//            // Extract Place ID from Google Maps URL
//            String placeId = googlePlacesService.extractPlaceIdFromGPageUrl(dto.getGoogleMapsUrl());
//
//            if (placeId == null) {
//                return ResponseEntity.badRequest()
//                        .body(Map.of("message", "Could not extract Place ID from URL. Please check the URL and try again."));
//            }
//
//            // Save Google connection
//            business.setGooglePlaceId(placeId);
//            business.setGoogleMapsUrl(dto.getGoogleMapsUrl());
//            businessRepository.save(business);
//
//            // Generate review URL
//            String reviewUrl = googlePlacesService.generateMapsUrl(placeId);
//
//            log.info("Google connected for business {}: Place ID = {}", id, placeId);
//
//            return ResponseEntity.ok(Map.of(
//                    "success", true,
//                    "placeId", placeId,
//                    "reviewUrl", reviewUrl,
//                    "message", "Google Business Profile connected successfully"
//            ));
//
//        } catch (Exception e) {
//            log.error("Error connecting Google for business {}: {}", id, e.getMessage());
//            return ResponseEntity.internalServerError()
//                    .body(Map.of("message", "Failed to connect Google account: " + e.getMessage()));
//        }
//    }
//
//    /**
//     * Disconnect Google Business Profile
//     * DELETE /api/v1/businesses/{id}/google-connection
//     */
//    @DeleteMapping("/{id}/google-connection")
//    public ResponseEntity<?> disconnectGoogle(
//            @PathVariable Long id,
//            @AuthenticationPrincipal UserDetails userDetails) {
//
//        try {
//            User user = (User) userDetails;
//
//            Business business = businessRepository.findByIdAndOrganizationId(
//                            id, user.getOrganization().getId())
//                    .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
//
//            // Remove Google connection
//            business.setGooglePlaceId(null);
//            business.setGoogleMapsUrl(null);
//            businessRepository.save(business);
//
//            log.info("Google disconnected for business {}", id);
//
//            return ResponseEntity.ok(Map.of(
//                    "success", true,
//                    "message", "Google Business Profile disconnected"
//            ));
//
//        } catch (Exception e) {
//            log.error("Error disconnecting Google for business {}: {}", id, e.getMessage());
//            return ResponseEntity.internalServerError()
//                    .body(Map.of("message", "Failed to disconnect Google account"));
//        }
//    }
//}