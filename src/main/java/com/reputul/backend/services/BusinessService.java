package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
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
public class BusinessService {

    @Autowired
    private FileStorageService fileStorageService;

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
     * Create new business
     */
    public Business createBusiness(Business business) {
        return businessRepository.save(business);
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
     */
    public Business updateBusiness(Long businessId, Business updatedBusiness, User user) {
        Business existingBusiness = getBusinessById(businessId, user);

        // Update fields
        existingBusiness.setName(updatedBusiness.getName());
        existingBusiness.setIndustry(updatedBusiness.getIndustry());
        existingBusiness.setPhone(updatedBusiness.getPhone());
        existingBusiness.setWebsite(updatedBusiness.getWebsite());
        existingBusiness.setAddress(updatedBusiness.getAddress());

        return businessRepository.save(existingBusiness);
    }

    /**
     * Update review platform configuration
     */
    public Business updateReviewPlatforms(Long businessId, String googlePlaceId,
                                          String facebookPageUrl, String yelpPageUrl, User user) {
        Business business = getBusinessById(businessId, user);

        business.setGooglePlaceId(googlePlaceId);
        business.setFacebookPageUrl(facebookPageUrl);
        business.setYelpPageUrl(yelpPageUrl);

        // Mark as configured if at least one platform is set
        boolean isConfigured = (googlePlaceId != null && !googlePlaceId.trim().isEmpty()) ||
                (facebookPageUrl != null && !facebookPageUrl.trim().isEmpty()) ||
                (yelpPageUrl != null && !yelpPageUrl.trim().isEmpty());

        business.setReviewPlatformsConfigured(isConfigured);

        return businessRepository.save(business);
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