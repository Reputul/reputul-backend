package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepo;
    private final ReputationService reputationService;

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
        return businessRepo.findByIdAndOwner(businessId, user)
                .orElseThrow(() -> new RuntimeException("Business not found or access denied"));
    }

    /**
     * Get business by ID without user check (for internal use)
     */
    public Business getBusinessById(Long businessId) {
        return businessRepo.findById(businessId)
                .orElseThrow(() -> new RuntimeException("Business not found"));
    }

    /**
     * Save business
     */
    public Business saveBusiness(Business business) {
        return businessRepo.save(business);
    }

    /**
     * Create new business
     */
    public Business createBusiness(Business business) {
        return businessRepo.save(business);
    }

    /**
     * Get all businesses for a user
     */
    public List<Business> getBusinessesByUser(User user) {
        return businessRepo.findByOwner(user);
    }

    /**
     * Delete business
     */
    public void deleteBusiness(Long businessId, User user) {
        Business business = getBusinessById(businessId, user);
        businessRepo.delete(business);
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

        return businessRepo.save(existingBusiness);
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

        return businessRepo.save(business);
    }

    /**
     * Check if business exists and belongs to user
     */
    public boolean existsByIdAndUser(Long businessId, User user) {
        return businessRepo.existsByIdAndOwner(businessId, user);
    }

    /**
     * Get businesses that need platform setup
     */
    public List<Business> getBusinessesNeedingPlatformSetup(User user) {
        return businessRepo.findByOwnerAndReviewPlatformsConfigured(user, false);
    }

    /**
     * Count businesses needing platform setup
     */
    public long countBusinessesNeedingPlatformSetup(User user) {
        return businessRepo.countByOwnerAndReviewPlatformsConfigured(user, false);
    }
}