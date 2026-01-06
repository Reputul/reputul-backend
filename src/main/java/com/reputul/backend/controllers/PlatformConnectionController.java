package com.reputul.backend.controllers;

import com.reputul.backend.exceptions.TokenExpiredException;
import com.reputul.backend.integrations.*;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.*;
import com.reputul.backend.services.ReviewSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for managing platform OAuth connections and review syncing
 * Supports: Google My Business, Facebook, Yelp
 */
@RestController
@RequestMapping("/api/v1/platforms")
@Slf4j
public class PlatformConnectionController {

    private final ChannelCredentialRepository credentialRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final ReviewSyncService reviewSyncService;
    private final Map<ChannelCredential.PlatformType, PlatformReviewClient> platformClients;

    private final RestTemplate restTemplate; // ← Add this field


    @Value("${google.oauth.redirect-uri}")
    private String googleRedirectUri;

    @Value("${facebook.oauth.redirect-uri}")
    private String facebookRedirectUri;

    @Value("${yelp.oauth.redirect-uri:${app.frontend.url}/oauth/callback/yelp}")
    private String yelpRedirectUri;

    public PlatformConnectionController(
            ChannelCredentialRepository credentialRepository,
            BusinessRepository businessRepository,
            UserRepository userRepository,
            ReviewSyncService reviewSyncService,
            List<PlatformReviewClient> clients,
            RestTemplate restTemplate) {

        this.credentialRepository = credentialRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.reviewSyncService = reviewSyncService;
        this.platformClients = clients.stream()
                .collect(Collectors.toMap(
                        PlatformReviewClient::getPlatformType,
                        c -> c));
        this.restTemplate = restTemplate;
    }

    /**
     * Get OAuth authorization URL for connecting a platform
     *
     * @param platformType Platform to connect (GOOGLE_MY_BUSINESS, FACEBOOK, etc.)
     * @param businessId Business to connect platform to
     * @param authentication Current user authentication
     * @return JSON with authorization URL
     */
    @GetMapping("/connect/{platformType}")
    public ResponseEntity<?> getConnectionUrl(
            @PathVariable String platformType,
            @RequestParam Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership - check if business belongs to user's organization
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized to connect platforms for this business"));
            }

            ChannelCredential.PlatformType platform = parsePlatformType(platformType);

            PlatformReviewClient client = platformClients.get(platform);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Platform not supported: " + platformType));
            }

            // Generate state token for CSRF protection
            String state = UUID.randomUUID().toString();
            String redirectUri = getRedirectUri(platform);

            String authUrl = client.getAuthorizationUrl(state, redirectUri);

            // CRITICAL FIX: Delete old credential if exists, then create fresh one
            // This avoids JPA dirty-checking issues when reusing entities
            Optional<ChannelCredential> existingCred = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, platform);

            if (existingCred.isPresent()) {
                log.info("Deleting old {} credential {} before creating new one",
                        platform, existingCred.get().getId());
                credentialRepository.delete(existingCred.get());
                credentialRepository.flush(); // Ensure delete completes before insert
            }

            // Always create fresh credential (avoid JPA entity reuse issues)
            ChannelCredential pendingCred = ChannelCredential.builder()
                    .organization(business.getOrganization())
                    .business(business)
                    .platformType(platform)
                    .status(ChannelCredential.CredentialStatus.PENDING)
                    .createdBy(user)
                    .build();

            // Set metadataJson directly to ensure it persists
            Map<String, Object> metadata = Map.of("state", state, "businessId", businessId);
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String metadataJson = mapper.writeValueAsString(metadata);
                pendingCred.setMetadataJson(metadataJson);
                pendingCred.setMetadata(metadata); // Also set transient field for in-memory use
            } catch (Exception e) {
                log.error("Failed to serialize metadata", e);
                throw new RuntimeException("Failed to serialize metadata");
            }

            log.info("Created new {} credential for business {} (state: {})",
                    platform, businessId, state);

            // Save and flush to ensure immediate persistence
            credentialRepository.saveAndFlush(pendingCred);

            log.info("Saved credential ID {} with metadataJson: {}",
                    pendingCred.getId(), pendingCred.getMetadataJson());

            return ResponseEntity.ok(Map.of(
                    "authUrl", authUrl,
                    "state", state,
                    "com/reputul/backend/platform", platform.name()
            ));

        } catch (Exception e) {
            log.error("Error generating connection URL for {}", platformType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Handle OAuth callback for Google My Business
     *
     * @param callbackData Code and state from OAuth provider
     * @param authentication Current user authentication
     * @return Success/error response
     */
    @PostMapping("/callback/google")
    public ResponseEntity<?> handleGoogleCallback(
            @RequestBody Map<String, String> callbackData,
            Authentication authentication) {

        return handleOAuthCallback(
                callbackData,
                authentication,
                ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS,
                googleRedirectUri
        );
    }

    /**
     * Handle OAuth callback for Facebook
     * UPDATED: Improved error logging and page fetching
     *
     * @param callbackData Code and state from OAuth provider
     * @param authentication Current user authentication
     * @return Success/error response
     */
    @PostMapping("/callback/facebook")
    public ResponseEntity<?> handleFacebookCallback(
            @RequestBody Map<String, String> callbackData,
            Authentication authentication) {

        try {
            String code = callbackData.get("code");
            String state = callbackData.get("state");

            log.info("🔵 Received Facebook OAuth callback - code: {}, state: {}",
                    code != null ? "present" : "missing", state);

            if (code == null || state == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Missing code or state"));
            }

            // Find pending credential by state token
            Optional<ChannelCredential> credOpt = credentialRepository.findByMetadataContaining(state);

            if (!credOpt.isPresent()) {
                log.error("❌ No credential found for state: {}", state);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid state - credential not found. Please try connecting again."));
            }

            ChannelCredential credential = credOpt.get();

            // Verify the credential is still PENDING
            if (credential.getStatus() != ChannelCredential.CredentialStatus.PENDING) {
                log.error("❌ Credential found but status is {} (expected PENDING)", credential.getStatus());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid credential state. Please try connecting again."));
            }

            // Verify the credential belongs to the authenticated user's organization
            User user = getUserFromAuth(authentication);
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Not authorized"));
            }

            // Verify platform type matches
            if (!credential.getPlatformType().equals(ChannelCredential.PlatformType.FACEBOOK)) {
                log.error("❌ Platform type mismatch: expected FACEBOOK, got {}",
                        credential.getPlatformType());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform type mismatch. Please try connecting again."));
            }

            // Get Facebook client
            FacebookClient facebookClient = (FacebookClient) platformClients.get(ChannelCredential.PlatformType.FACEBOOK);
            if (facebookClient == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Facebook client not available"));
            }

            // Exchange authorization code for access token (gets long-lived token)
            log.info("🔄 Exchanging code for Facebook access token");
            OAuthTokenResponse tokenResponse = facebookClient.exchangeCodeForToken(code, facebookRedirectUri);

            // ========================================
            // Fetch Facebook pages and get page access token
            // ========================================
            String userAccessToken = tokenResponse.getAccessToken();
            log.info("✅ Got user access token (length: {})", userAccessToken.length());

            try {
                // Get user's Facebook pages
                String pagesUrl = String.format(
                        "https://graph.facebook.com/v18.0/me/accounts?fields=id,name,access_token,category&access_token=%s",
                        userAccessToken
                );

                log.info("📡 Fetching Facebook pages from Graph API");
                ResponseEntity<String> pagesResponse = restTemplate.getForEntity(pagesUrl, String.class);

                log.info("📥 Facebook API response status: {}", pagesResponse.getStatusCode());
                log.info("📥 Facebook API response body: {}", pagesResponse.getBody());

                if (!pagesResponse.getStatusCode().is2xxSuccessful() || pagesResponse.getBody() == null) {
                    log.error("❌ Failed to fetch Facebook pages - status: {}", pagesResponse.getStatusCode());
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "success", false,
                                    "error", "Failed to fetch Facebook pages. Please try reconnecting."
                            ));
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode pagesData = mapper.readTree(pagesResponse.getBody());

                // Check for error in response
                if (pagesData.has("error")) {
                    com.fasterxml.jackson.databind.JsonNode error = pagesData.get("error");
                    String errorMessage = error.has("message") ? error.get("message").asText() : "Unknown error";
                    log.error("❌ Facebook API error: {}", errorMessage);
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "success", false,
                                    "error", "Facebook API error: " + errorMessage
                            ));
                }

                com.fasterxml.jackson.databind.JsonNode pagesArray = pagesData.get("data");

                if (pagesArray == null) {
                    log.error("❌ No 'data' field in Facebook response");
                    log.error("Full response: {}", pagesData.toString());
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "success", false,
                                    "error", "Invalid response from Facebook. Please try again."
                            ));
                }

                if (pagesArray.size() == 0) {
                    log.warn("⚠️ Facebook returned 0 pages");
                    log.warn("Full response: {}", pagesData.toString());
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "success", false,
                                    "error", "No Facebook pages found. Please make sure you're an admin of a Facebook page and granted all permissions during authorization."
                            ));
                }

                log.info("✅ Found {} Facebook page(s)", pagesArray.size());

                // Log all pages for debugging
                for (int i = 0; i < pagesArray.size(); i++) {
                    com.fasterxml.jackson.databind.JsonNode page = pagesArray.get(i);
                    log.info("  Page {}: {} (ID: {})",
                            i + 1,
                            page.has("name") ? page.get("name").asText() : "Unknown",
                            page.has("id") ? page.get("id").asText() : "Unknown");
                }

                // Get first page (in future, you could let user select which page)
                com.fasterxml.jackson.databind.JsonNode firstPage = pagesArray.get(0);

                if (!firstPage.has("id") || !firstPage.has("name") || !firstPage.has("access_token")) {
                    log.error("❌ First page missing required fields");
                    log.error("Page data: {}", firstPage.toString());
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "success", false,
                                    "error", "Invalid page data from Facebook. Please try again."
                            ));
                }

                String pageId = firstPage.get("id").asText();
                String pageName = firstPage.get("name").asText();
                String pageAccessToken = firstPage.get("access_token").asText();

                log.info("✅ Selected Facebook page: {} (ID: {})", pageName, pageId);

                // Update credential with tokens AND page info
                credential.setAccessToken(userAccessToken); // User token (for getting pages)
                credential.setRefreshToken(null); // Facebook doesn't use refresh tokens

                if (tokenResponse.getExpiresIn() != null) {
                    credential.setTokenExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                            .plusSeconds(tokenResponse.getExpiresIn()));
                }

                // Store page-specific data in metadata
                Map<String, Object> metadata = credential.getMetadata();
                if (metadata == null) {
                    metadata = new java.util.HashMap<>();
                }

                metadata.put("pageId", pageId);
                metadata.put("pageName", pageName);
                metadata.put("pageUrl", "https://facebook.com/" + pageId);
                metadata.put("pageAccessToken", pageAccessToken); // ← Critical for API calls!

                // Serialize metadata to JSON
                String metadataJson = mapper.writeValueAsString(metadata);
                credential.setMetadataJson(metadataJson);
                credential.setMetadata(metadata);

                credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
                credential.setNextSyncScheduled(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

                credentialRepository.save(credential);

                log.info("✅ Facebook connected successfully for business {}, credential ID: {}, page: {}",
                        credential.getBusiness().getId(), credential.getId(), pageName);

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Facebook connected successfully",
                        "credentialId", credential.getId(),
                        "pageName", pageName
                ));

            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.error("❌ JSON parsing error", e);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(
                                "success", false,
                                "error", "Failed to parse Facebook response: " + e.getMessage()
                        ));
            } catch (HttpClientErrorException e) {
                log.error("❌ Facebook API HTTP error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(
                                "success", false,
                                "error", "Facebook API error: " + e.getMessage()
                        ));
            } catch (Exception e) {
                log.error("❌ Error fetching Facebook page information", e);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of(
                                "success", false,
                                "error", "Failed to get Facebook page information: " + e.getMessage()
                        ));
            }

        } catch (PlatformIntegrationException e) {
            log.error("❌ Facebook OAuth callback error", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("❌ Facebook OAuth callback unexpected error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Connection failed: " + e.getMessage()));
        }
    }

    /**
     * Generic OAuth callback handler for all platforms
     * Handles token exchange and credential activation
     */
    private ResponseEntity<?> handleOAuthCallback(
            Map<String, String> callbackData,
            Authentication authentication,
            ChannelCredential.PlatformType platformType,
            String redirectUri) {

        try {
            String code = callbackData.get("code");
            String state = callbackData.get("state");

            log.info("Received {} OAuth callback - code: {}, state: {}",
                    platformType, code != null ? "present" : "missing", state);

            if (code == null || state == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Missing code or state"));
            }

            // Find pending credential by state token
            Optional<ChannelCredential> credOpt = credentialRepository.findByMetadataContaining(state);

            if (!credOpt.isPresent()) {
                log.error("No credential found for state: {}", state);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid state - credential not found. Please try connecting again."));
            }

            ChannelCredential credential = credOpt.get();

            // CRITICAL FIX: Verify the credential is still PENDING (not an old revoked one)
            if (credential.getStatus() != ChannelCredential.CredentialStatus.PENDING) {
                log.error("Credential found but status is {} (expected PENDING)", credential.getStatus());
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Invalid credential state. Please try connecting again."));
            }

            // Verify the credential belongs to the authenticated user's organization
            User user = getUserFromAuth(authentication);
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "error", "Not authorized"));
            }

            // CRITICAL FIX: Verify platform type matches
            if (!credential.getPlatformType().equals(platformType)) {
                log.error("Platform type mismatch: expected {}, got {}",
                        credential.getPlatformType(), platformType);
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform type mismatch. Please try connecting again."));
            }

            // CRITICAL FIX: Verify businessId from metadata matches the credential's business
            Map<String, Object> metadata = credential.getMetadata();
            if (metadata != null && metadata.containsKey("businessId")) {
                Long metadataBusinessId = ((Number) metadata.get("businessId")).longValue();
                if (!metadataBusinessId.equals(credential.getBusiness().getId())) {
                    log.error("Business ID mismatch: metadata has {}, credential has {}",
                            metadataBusinessId, credential.getBusiness().getId());
                    return ResponseEntity.badRequest()
                            .body(Map.of("success", false, "error", "Business mismatch. Please try connecting again."));
                }
            }

            PlatformReviewClient client = platformClients.get(platformType);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false, "error", "Platform not supported"));
            }

            // Exchange authorization code for access token
            log.info("Exchanging code for {} access token", platformType);
            OAuthTokenResponse tokenResponse = client.exchangeCodeForToken(code, redirectUri);

            // Update credential with tokens
            credential.setAccessToken(tokenResponse.getAccessToken());
            credential.setRefreshToken(tokenResponse.getRefreshToken());

            if (platformType == ChannelCredential.PlatformType.GOOGLE_MY_BUSINESS) {
                try {
                    log.info("Fetching Google account and location info to cache...");

                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

                    // Fetch accounts
                    String accountsUrl = "https://mybusinessaccountmanagement.googleapis.com/v1/accounts";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setBearerAuth(tokenResponse.getAccessToken());
                    HttpEntity<String> entity = new HttpEntity<>(headers);

                    ResponseEntity<String> accountsResponse = restTemplate.exchange(
                            accountsUrl, HttpMethod.GET, entity, String.class
                    );

                    com.fasterxml.jackson.databind.JsonNode accountsData = mapper.readTree(accountsResponse.getBody());
                    com.fasterxml.jackson.databind.JsonNode accounts = accountsData.get("accounts");

                    if (accounts != null && accounts.size() > 0) {
                        String accountName = accounts.get(0).get("name").asText();
                        log.info("Found Google account: {}", accountName);

                        // Fetch locations for this account
                        String locationsUrl = String.format(
                                "https://mybusinessbusinessinformation.googleapis.com/v1/%s/locations",
                                accountName
                        );

                        ResponseEntity<String> locationsResponse = restTemplate.exchange(
                                locationsUrl, HttpMethod.GET, entity, String.class
                        );

                        com.fasterxml.jackson.databind.JsonNode locationsData = mapper.readTree(locationsResponse.getBody());
                        com.fasterxml.jackson.databind.JsonNode locations = locationsData.get("locations");

                        if (locations != null && locations.size() > 0) {
                            // Get first location
                            com.fasterxml.jackson.databind.JsonNode firstLocation = locations.get(0);
                            String locationName = firstLocation.get("name").asText();
                            String locationTitle = firstLocation.has("title") ? firstLocation.get("title").asText() : "Unknown";

                            log.info("Found location: {} ({})", locationTitle, locationName);

                            // CORRECTED: Get existing metadata and add to it
                            Map<String, Object> existingMetadata = credential.getMetadata();
                            if (existingMetadata == null) {
                                existingMetadata = new HashMap<>();
                            }

                            // Add Google-specific fields
                            existingMetadata.put("accountName", accountName);
                            existingMetadata.put("locationName", locationName);
                            existingMetadata.put("locationTitle", locationTitle);

                            // CORRECTED: Just call setMetadata (not setMetadataJson)
                            credential.setMetadata(existingMetadata);

                            log.info("✅ Cached Google account and location - future syncs will avoid rate limits!");
                        } else {
                            log.warn("No locations found for Google account");
                        }
                    } else {
                        log.warn("No Google accounts found");
                    }

                } catch (Exception e) {
                    // Don't fail OAuth if caching fails - just log warning
                    log.error("Failed to cache Google account/location info: {}", e.getMessage());
                    log.warn("Review syncing may encounter rate limits without cached metadata");
                    // Continue with OAuth success
                }
            }

            if (tokenResponse.getExpiresIn() != null) {
                credential.setTokenExpiresAt(OffsetDateTime.now(ZoneOffset.UTC)
                        .plusSeconds(tokenResponse.getExpiresIn()));
            }

            credential.setStatus(ChannelCredential.CredentialStatus.ACTIVE);
            credential.setNextSyncScheduled(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

            credentialRepository.save(credential);

            log.info("Platform {} connected successfully for business {}, credential ID: {}",
                    platformType, credential.getBusiness().getId(), credential.getId());

            try {
                log.info("🚀 Triggering immediate review sync for newly connected platform...");
                reviewSyncService.syncPlatformReviews(credential, credential.getBusiness());
                log.info("✅ Initial sync completed successfully");
            } catch (Exception e) {
                log.error("Initial sync failed (non-fatal): {}", e.getMessage());
                // Don't fail OAuth if initial sync fails - user can retry later
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", platformType.name() + " connected successfully",
                    "credentialId", credential.getId(),
                    "initialSyncTriggered", true // ← Let frontend know sync started
            ));

        } catch (PlatformIntegrationException e) {
            log.error("{} OAuth callback error", platformType, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("{} OAuth callback unexpected error", platformType, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Connection failed: " + e.getMessage()));
        }
    }

    /**
     * Get all connected platforms for a business
     *
     * @param businessId Business ID
     * @param authentication Current user
     * @return List of connected platforms with status
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<?> getConnectedPlatforms(
            @PathVariable Long businessId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            List<ChannelCredential> credentials = credentialRepository
                    .findByBusinessId(businessId);

            List<Map<String, Object>> platforms = credentials.stream()
                    .map(cred -> {
                        Map<String, Object> platformData = new HashMap<>();
                        platformData.put("id", cred.getId());
                        platformData.put("platform", cred.getPlatformType().name());
                        platformData.put("status", cred.getStatus().name());
                        platformData.put("lastSyncAt", cred.getLastSyncAt() != null ?
                                cred.getLastSyncAt().toString() : null);
                        platformData.put("lastSyncStatus", cred.getLastSyncStatus() != null ?
                                cred.getLastSyncStatus() : "NEVER_SYNCED");
                        platformData.put("tokenExpired", cred.isTokenExpired());
                        platformData.put("needsRefresh", cred.needsRefresh());

                        // Include page info for Facebook
                        if (cred.getPlatformType() == ChannelCredential.PlatformType.FACEBOOK) {
                            Map<String, Object> metadata = cred.getMetadata();
                            if (metadata != null) {
                                platformData.put("pageName", metadata.get("pageName"));
                                platformData.put("pageId", metadata.get("pageId"));
                            }
                        }

                        return platformData;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(platforms);

        } catch (Exception e) {
            log.error("Error fetching connected platforms", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Initiate OAuth connection flow
     * Frontend-friendly endpoint that accepts POST with JSON body
     *
     * @param request JSON body with platform and businessId
     * @param authentication Current user authentication
     * @return JSON with authorization URL
     */
    @PostMapping("/oauth/initiate")
    public ResponseEntity<?> initiateOAuthConnection(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        try {
            String platformType = (String) request.get("com/reputul/backend/platform");
            Long businessId = ((Number) request.get("businessId")).longValue();

            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership - check if business belongs to user's organization
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized to connect platforms for this business"));
            }

            ChannelCredential.PlatformType platform = parsePlatformType(platformType);

            PlatformReviewClient client = platformClients.get(platform);
            if (client == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Platform not supported: " + platformType));
            }

            // Generate state token for CSRF protection
            String state = UUID.randomUUID().toString();
            String redirectUri = getRedirectUri(platform);

            String authUrl = client.getAuthorizationUrl(state, redirectUri);

            // CRITICAL FIX: Delete old credential if exists, then create fresh one
            // This avoids JPA dirty-checking issues when reusing entities
            Optional<ChannelCredential> existingCred = credentialRepository
                    .findByBusinessIdAndPlatformType(businessId, platform);

            if (existingCred.isPresent()) {
                log.info("Deleting old {} credential {} before creating new one",
                        platform, existingCred.get().getId());
                credentialRepository.delete(existingCred.get());
                credentialRepository.flush(); // Ensure delete completes before insert
            }

            // Always create fresh credential (avoid JPA entity reuse issues)
            ChannelCredential pendingCred = ChannelCredential.builder()
                    .organization(business.getOrganization())
                    .business(business)
                    .platformType(platform)
                    .status(ChannelCredential.CredentialStatus.PENDING)
                    .createdBy(user)
                    .build();

            // Set metadataJson directly to ensure it persists
            Map<String, Object> metadata = Map.of("state", state, "businessId", businessId);
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                String metadataJson = mapper.writeValueAsString(metadata);
                pendingCred.setMetadataJson(metadataJson);
                pendingCred.setMetadata(metadata); // Also set transient field for in-memory use
            } catch (Exception e) {
                log.error("Failed to serialize metadata", e);
                throw new RuntimeException("Failed to serialize metadata");
            }

            log.info("Created new {} credential for business {} (state: {})",
                    platform, businessId, state);

            // Save and flush to ensure immediate persistence
            credentialRepository.saveAndFlush(pendingCred);

            log.info("Saved credential ID {} with metadataJson: {}",
                    pendingCred.getId(), pendingCred.getMetadataJson());

            return ResponseEntity.ok(Map.of(
                    "authUrl", authUrl,
                    "state", state,
                    "com/reputul/backend/platform", platform.name()
            ));

        } catch (Exception e) {
            log.error("Error initiating OAuth connection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Manually trigger sync for a platform
     *
     * UPDATED: Removed manual token refresh - ReviewSyncService now handles this automatically
     * TokenExpiredException is allowed to propagate to GlobalExceptionHandler
     *
     * @param credentialId Credential ID to sync
     * @param authentication Current user
     * @return Sync job results
     */
    @PostMapping("/{credentialId}/sync")
    public ResponseEntity<?> triggerSync(
            @PathVariable Long credentialId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            ChannelCredential credential = credentialRepository.findById(credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("Credential not found"));

            // Verify ownership
            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            // REMOVED: Manual token refresh logic - ReviewSyncService handles this now
            // The sync service will automatically refresh/extend tokens as needed

            ReviewSyncJob job = reviewSyncService.syncPlatformReviews(
                    credential, credential.getBusiness());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "jobId", job.getId(),
                    "status", job.getStatus().name(),
                    "reviewsFetched", job.getReviewsFetched() != null ? job.getReviewsFetched() : 0,
                    "newCount", job.getReviewsNew() != null ? job.getReviewsNew() : 0,
                    "updatedCount", job.getReviewsUpdated() != null ? job.getReviewsUpdated() : 0
            ));

        } catch (TokenExpiredException e) {
            // Let TokenExpiredException propagate to GlobalExceptionHandler
            // GlobalExceptionHandler will return proper 401 response
            throw e;

        } catch (Exception e) {
            log.error("Error triggering sync", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Disconnect a platform
     *
     * @param credentialId Credential ID to disconnect
     * @param authentication Current user
     * @return Success response
     */
    @DeleteMapping("/{credentialId}")
    public ResponseEntity<?> disconnectPlatform(
            @PathVariable Long credentialId,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            ChannelCredential credential = credentialRepository.findById(credentialId)
                    .orElseThrow(() -> new IllegalArgumentException("Credential not found"));

            if (!credential.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            log.info("Disconnecting platform {} for business {}",
                    credential.getPlatformType(), credential.getBusiness().getId());

            // CHANGED: Delete the credential instead of marking as REVOKED
            // This prevents finding old credentials during reconnection
            credentialRepository.delete(credential);

            return ResponseEntity.ok(Map.of("success", true));

        } catch (Exception e) {
            log.error("Error disconnecting platform", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Reorder connected platforms
     * Updates the display_order for drag-and-drop reordering
     *
     * @param businessId Business ID
     * @param request JSON body with ordered platform IDs
     * @param authentication Current user
     * @return Success response
     */
    @PutMapping("/business/{businessId}/reorder")
    public ResponseEntity<?> reorderPlatforms(
            @PathVariable Long businessId,
            @RequestBody Map<String, Object> request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Business not found"));

            // Verify ownership
            if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Not authorized"));
            }

            // Get ordered platform IDs from request
            @SuppressWarnings("unchecked")
            List<Number> platformIds = (List<Number>) request.get("platformIds");

            if (platformIds == null || platformIds.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Platform IDs are required"));
            }

            log.info("Reordering platforms for business {}: {}", businessId, platformIds);

            // Update display_order for each platform
            for (int i = 0; i < platformIds.size(); i++) {
                Long platformId = platformIds.get(i).longValue();
                Optional<ChannelCredential> credOpt = credentialRepository.findById(platformId);

                if (credOpt.isPresent()) {
                    ChannelCredential cred = credOpt.get();

                    // Verify this credential belongs to the business
                    if (!cred.getBusiness().getId().equals(businessId)) {
                        log.warn("Platform {} does not belong to business {}", platformId, businessId);
                        continue;
                    }

                    cred.setDisplayOrder(i);
                    credentialRepository.save(cred);
                    log.debug("Set display order {} for platform {}", i, platformId);
                }
            }

            log.info("Successfully reordered {} platforms", platformIds.size());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Platform order updated"
            ));

        } catch (Exception e) {
            log.error("Error reordering platforms", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // ============ Helper Methods ============

    private User getUserFromAuth(Authentication auth) {
        String email = auth.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    private ChannelCredential.PlatformType parsePlatformType(String platformType) {
        try {
            return ChannelCredential.PlatformType.valueOf(platformType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid platform type: " + platformType);
        }
    }

    private String getRedirectUri(ChannelCredential.PlatformType platform) {
        switch (platform) {
            case GOOGLE_MY_BUSINESS:
                return googleRedirectUri;
            case FACEBOOK:
                return facebookRedirectUri;
            case YELP:
                return yelpRedirectUri;
            default:
                log.warn("Unknown platform type: {}, using default redirect URI", platform);
                return googleRedirectUri; // Fallback to Google URI
        }
    }
}