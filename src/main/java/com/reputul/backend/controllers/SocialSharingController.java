package com.reputul.backend.controllers;

import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Controller for social media sharing functionality
 * Handles posting reviews to Facebook, Twitter, etc.
 */
@RestController
@RequestMapping("/api/v1/social")
@Slf4j
public class SocialSharingController {

    private final ReviewRepository reviewRepository;
    private final BusinessRepository businessRepository;
    private final ChannelCredentialRepository credentialRepository;
    private final UserRepository userRepository;
    private final RestTemplate restTemplate;

    public SocialSharingController(
            ReviewRepository reviewRepository,
            BusinessRepository businessRepository,
            ChannelCredentialRepository credentialRepository,
            UserRepository userRepository,
            RestTemplate restTemplate) {

        this.reviewRepository = reviewRepository;
        this.businessRepository = businessRepository;
        this.credentialRepository = credentialRepository;
        this.userRepository = userRepository;
        this.restTemplate = restTemplate;
    }

    /**
     * Share a review to Facebook business page
     *
     * @param request Request body containing reviewId, postText, businessId
     * @param authentication Current user authentication
     * @return Success response with Facebook post ID
     */
    @PostMapping("/facebook/share-review")
    public ResponseEntity<?> shareReviewToFacebook(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);

            // Parse request
            Long reviewId = ((Number) request.get("reviewId")).longValue();
            String postText = (String) request.get("postText");
            Long businessId = ((Number) request.get("businessId")).longValue();

            log.info("Sharing review {} to Facebook for business {}", reviewId, businessId);

            // Verify review exists and belongs to user's organization
            Review review = reviewRepository.findById(reviewId)
                    .orElseThrow(() -> new IllegalArgumentException("Review not found"));

            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            // Find Facebook credential for this business
            ChannelCredential fbCredential = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, ChannelCredential.PlatformType.FACEBOOK)
                    .orElseThrow(() -> new IllegalArgumentException("Facebook not connected for this business"));

            // Get page access token from metadata
            Map<String, Object> metadata = fbCredential.getMetadata();
            if (metadata == null) {
                throw new IllegalStateException("Facebook credential metadata not found");
            }

            String pageAccessToken = (String) metadata.get("pageAccessToken");
            String pageId = (String) metadata.get("pageId");

            if (pageAccessToken == null || pageId == null) {
                throw new IllegalStateException("Facebook page credentials incomplete. Please reconnect Facebook.");
            }

            // Post to Facebook Graph API
            String facebookPostUrl = String.format("https://graph.facebook.com/v18.0/%s/feed", pageId);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("message", postText);
            params.add("access_token", pageAccessToken);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(params, headers);

            ResponseEntity<Map> facebookResponse = restTemplate.postForEntity(
                    facebookPostUrl,
                    requestEntity,
                    Map.class
            );

            if (!facebookResponse.getStatusCode().is2xxSuccessful() || facebookResponse.getBody() == null) {
                throw new RuntimeException("Failed to post to Facebook");
            }

            String postId = (String) facebookResponse.getBody().get("id");

            log.info("Successfully posted review {} to Facebook. Post ID: {}", reviewId, postId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Review shared to Facebook successfully",
                    "postId", postId,
                    "postUrl", String.format("https://facebook.com/%s/posts/%s", pageId, postId.split("_")[1])
            ));

        } catch (IllegalArgumentException e) {
            log.error("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (IllegalStateException e) {
            log.error("Configuration error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {
            log.error("Error sharing review to Facebook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to share to Facebook: " + e.getMessage()));
        }
    }

    /**
     * Get Facebook page access token and page ID for a business
     * (Useful for debugging/testing)
     *
     * @param businessId Business ID
     * @param authentication Current user authentication
     * @return Page info
     */
    @GetMapping("/facebook/page-info/{businessId}")
    public ResponseEntity<?> getFacebookPageInfo(
            @PathVariable Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);

            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            ChannelCredential fbCredential = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, ChannelCredential.PlatformType.FACEBOOK)
                    .orElseThrow(() -> new IllegalArgumentException("Facebook not connected"));

            Map<String, Object> metadata = fbCredential.getMetadata();
            if (metadata == null) {
                return ResponseEntity.ok(Map.of("connected", false));
            }

            return ResponseEntity.ok(Map.of(
                    "connected", true,
                    "pageId", metadata.get("pageId"),
                    "pageName", metadata.get("pageName"),
                    "pageUrl", metadata.get("pageUrl"),
                    "hasToken", metadata.containsKey("pageAccessToken")
            ));

        } catch (Exception e) {
            log.error("Error getting Facebook page info", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Helper method
    private User getUserFromAuth(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }
}