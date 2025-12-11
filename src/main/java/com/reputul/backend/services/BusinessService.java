package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessService {

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private GooglePlacesService googlePlacesService; // ADDED: Google Places auto-detection

    @Value("${app.backend.url}")
    private String backendUrl;

    private final BusinessRepository businessRepository;
    private final ReputationService reputationService;
    private final UserRepository userRepository;

    /**
     * Triggers a full recalculation of reputation score and badge for the given business.
     */
    public void updateReputationScore(Business business) {
        reputationService.updateBusinessReputationAndBadge(business.getId());
    }

    /**
     * Get business by ID for a specific user (security check)
     */
    public Business getBusinessById(Long businessId, User user) {
        return businessRepository.findByIdAndUser(businessId, user)
                .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
    }

    /**
     * Get business by ID without user check (for internal use)
     */
    public Business getBusinessById(Long businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new RuntimeException("Business not found"));
    }

    /**
     * Save business
     */
    public Business saveBusiness(Business business) {
        return businessRepository.save(business);
    }

    /**
     * Create new business with Google Places auto-detection
     *
     * CHANGED: Now auto-detects Place ID and generates review URLs
     */
    public Business createBusiness(Business business) {
        // Save business first to get ID
        Business savedBusiness = businessRepository.save(business);

        // ADDED: Auto-detect Google Place ID if not manually provided
        autoDetectAndConfigureGooglePlaces(savedBusiness);

        // Save again with Google Places data
        return businessRepository.save(savedBusiness);
    }

    /**
     * Get all businesses for a user
     */
    public List<Business> getBusinessesByUser(User user) {
        return businessRepository.findByUser(user);
    }

    /**
     * Delete business
     */
    public void deleteBusiness(Long businessId, User user) {
        Business business = getBusinessById(businessId, user);
        businessRepository.delete(business);
    }

    /**
     * Update business
     *
     * CHANGED: Refreshes Google Places data if address changes
     */
    public Business updateBusiness(Long businessId, Business updatedBusiness, User user) {
        Business existingBusiness = getBusinessById(businessId, user);

        // Check if address changed
        boolean addressChanged = !isSameAddress(existingBusiness.getAddress(), updatedBusiness.getAddress());

        // Update fields
        existingBusiness.setName(updatedBusiness.getName());
        existingBusiness.setIndustry(updatedBusiness.getIndustry());
        existingBusiness.setPhone(updatedBusiness.getPhone());
        existingBusiness.setWebsite(updatedBusiness.getWebsite());
        existingBusiness.setAddress(updatedBusiness.getAddress());

        // ADDED: If address changed, re-detect Google Place ID
        if (addressChanged && existingBusiness.getGooglePlaceAutoDetected()) {
            autoDetectAndConfigureGooglePlaces(existingBusiness);
        }

        return businessRepository.save(existingBusiness);
    }

    /**
     * Helper to check if address changed
     */
    private boolean isSameAddress(String addr1, String addr2) {
        if (addr1 == null && addr2 == null) return true;
        if (addr1 == null || addr2 == null) return false;
        return addr1.trim().equalsIgnoreCase(addr2.trim());
    }

    /**
     * Update review platform configuration
     *
     * CHANGED: Handles manual Google Place ID and g.page URL overrides
     */
    public Business updateReviewPlatforms(Long businessId, String googlePlaceId,
                                          String facebookPageUrl, String yelpPageUrl, User user) {
        return updateReviewPlatforms(businessId, googlePlaceId, null, facebookPageUrl, yelpPageUrl, user);
    }

    /**
     * Update review platform configuration with g.page short URL support
     *
     * NEW METHOD: Supports both Place ID and g.page short URL
     */
    public Business updateReviewPlatforms(Long businessId, String googlePlaceId, String googleShortUrl,
                                          String facebookPageUrl, String yelpPageUrl, User user) {
        Business business = getBusinessById(businessId, user);

        // ADDED: Handle manual Place ID override
        if (googlePlaceId != null && !googlePlaceId.trim().isEmpty()) {
            // User manually provided Place ID
            business.setGooglePlaceId(googlePlaceId);
            business.setGoogleReviewUrl(googlePlacesService.generateReviewUrl(googlePlaceId));
            business.setGooglePlaceAutoDetected(false); // Manually entered
            business.setGooglePlaceLastSynced(OffsetDateTime.now());
        }

        // ADDED: Handle g.page short URL
        if (googleShortUrl != null && !googleShortUrl.trim().isEmpty()) {
            // User provided g.page/r/XXX/review URL
            business.setGoogleReviewShortUrl(googleShortUrl);

            // Try to extract identifier (not a real Place ID, but stored for reference)
            String extractedId = googlePlacesService.extractPlaceIdFromGPageUrl(googleShortUrl);
            if (extractedId != null && business.getGooglePlaceId() == null) {
                // Only set if Place ID is not already set
                business.setGooglePlaceId("gpage_" + extractedId); // Prefix to indicate it's from g.page
            }
        }

        // ADDED: If neither provided, try auto-detection
        if ((googlePlaceId == null || googlePlaceId.trim().isEmpty()) &&
                (googleShortUrl == null || googleShortUrl.trim().isEmpty()) &&
                business.getGooglePlaceId() == null) {
            autoDetectAndConfigureGooglePlaces(business);
        }

        // Set other platforms
        business.setFacebookPageUrl(facebookPageUrl);
        business.setYelpPageUrl(yelpPageUrl);

        // Mark as configured if at least one platform is set
        boolean isConfigured = (business.getGooglePlaceId() != null && !business.getGooglePlaceId().trim().isEmpty()) ||
                (googleShortUrl != null && !googleShortUrl.trim().isEmpty()) ||
                (facebookPageUrl != null && !facebookPageUrl.trim().isEmpty()) ||
                (yelpPageUrl != null && !yelpPageUrl.trim().isEmpty());

        business.setReviewPlatformsConfigured(isConfigured);

        return businessRepository.save(business);
    }

    /**
     * NEW METHOD: Auto-detect Google Place ID and configure URLs
     *
     * This is the core auto-detection logic following Reputul's recommended approach
     */
    private void autoDetectAndConfigureGooglePlaces(Business business) {
        if (business == null || business.getName() == null || business.getAddress() == null) {
            // Generate fallback search URL
            if (business != null && business.getName() != null) {
                business.setGoogleSearchUrl(googlePlacesService.generateSearchUrl(business));
            }
            return;
        }

        try {
            // Call Google Places API
            GooglePlacesService.PlacesLookupResult result = googlePlacesService.autoDetectPlaceId(business);

            if (result != null && result.isSuccess()) {
                // SUCCESS: Apply Place ID and generate review URL
                googlePlacesService.applyPlacesDataToBusiness(business, result);
                log.info("✅ Auto-detected Place ID for business: {} → {}",
                        business.getName(), result.getPlaceId());
            } else {
                // FALLBACK: Generate search URL
                business.setGoogleSearchUrl(googlePlacesService.generateSearchUrl(business));
                log.info("⚠️ Could not auto-detect Place ID for business: {}, using search URL fallback",
                        business.getName());
            }
        } catch (Exception e) {
            // ERROR FALLBACK: Generate search URL
            business.setGoogleSearchUrl(googlePlacesService.generateSearchUrl(business));
            log.error("❌ Error auto-detecting Place ID for business: {}, using search URL fallback",
                    business.getName(), e);
        }
    }

    /**
     * NEW METHOD: Manually refresh Google Places data
     *
     * Useful for businesses that want to re-sync with Google
     */
    public Business refreshGooglePlacesData(Long businessId, User user) {
        Business business = getBusinessById(businessId, user);

        // Only refresh if originally auto-detected (don't override manual entries)
        if (business.getGooglePlaceAutoDetected() != null && business.getGooglePlaceAutoDetected()) {
            autoDetectAndConfigureGooglePlaces(business);
            return businessRepository.save(business);
        }

        return business;
    }

    /**
     * Check if business exists and belongs to user
     */
    public boolean existsByIdAndUser(Long businessId, User user) {
        return businessRepository.existsByIdAndUser(businessId, user);
    }

    /**
     * Get businesses that need platform setup
     */
    public List<Business> getBusinessesNeedingPlatformSetup(User user) {
        return businessRepository.findByUserAndReviewPlatformsConfigured(user, false);
    }

    /**
     * Count businesses needing platform setup
     */
    public long countBusinessesNeedingPlatformSetup(User user) {
        return businessRepository.countByUserAndReviewPlatformsConfigured(user, false);
    }

    /**
     * Update business logo
     */
    @Transactional
    public Business updateLogo(Long businessId, MultipartFile file, Authentication authentication) throws IOException {
        Business business = getBusinessByIdAndCheckOwnership(businessId, authentication);

        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        if (file.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("File size exceeds 5MB limit");
        }

        // Delete old logo if exists
        if (business.getLogoFilename() != null) {
            try {
                fileStorageService.deleteFile(business.getLogoFilename());
            } catch (IOException e) {
                // Log but don't fail - old file might already be deleted
                System.err.println("Could not delete old logo: " + e.getMessage());
            }
        }

        // Store new logo
        String filename = fileStorageService.storeFile(file, "business_" + businessId);

        business.setLogoFilename(filename);
        business.setLogoUrl(backendUrl + "/api/v1/files/logos/" + filename);
        business.setLogoContentType(file.getContentType());
        business.setLogoUploadedAt(OffsetDateTime.now());

        return businessRepository.save(business);
    }

    /**
     * Delete business logo
     */
    @Transactional
    public Business deleteLogo(Long businessId, Authentication authentication) throws IOException {
        Business business = getBusinessByIdAndCheckOwnership(businessId, authentication);

        if (business.getLogoFilename() != null) {
            fileStorageService.deleteFile(business.getLogoFilename());
        }

        business.setLogoFilename(null);
        business.setLogoUrl(null);
        business.setLogoContentType(null);
        business.setLogoUploadedAt(null);

        return businessRepository.save(business);
    }

    // Helper method to check ownership
    private Business getBusinessByIdAndCheckOwnership(Long businessId, Authentication authentication) {
        Business business = businessRepository.findById(businessId)
                .orElseThrow(() -> new RuntimeException("Business not found"));

        // Get current user's organization
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Verify business belongs to user's organization
        if (!business.getOrganization().getId().equals(user.getOrganization().getId())) {
            throw new RuntimeException("Access denied");
        }

        return business;
    }
}