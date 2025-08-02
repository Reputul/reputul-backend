package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReputationService {

    private final ReviewRepository reviewRepo;
    private final BusinessRepository businessRepo;
    private final BadgeService badgeService;

    public ReputationService(
            ReviewRepository reviewRepo,
            BusinessRepository businessRepo,
            BadgeService badgeService
    ) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
        this.badgeService = badgeService;
    }

    /**
     * Calculates a weighted reputation score for a business based on average rating and review volume.
     */
    public double getReputationScore(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) return 0.0;

        double average = reviews.stream()
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);

        double volumeWeight = Math.log10(reviews.size() + 1); // smooths early low-volume spikes

        return Math.round(average * volumeWeight * 10.0) / 10.0;
    }

    /**
     * Recalculates and updates both the reputation score and the badge for a business.
     */
    public void updateBusinessReputationAndBadge(Long businessId) {
        Business business = businessRepo.findById(businessId)
                .orElseThrow(() -> new RuntimeException("Business not found"));

        double score = getReputationScore(businessId);
        business.setReputationScore(score);

        String badge = badgeService.updateBusinessBadge(business); // Sets the badge field
        businessRepo.save(business); // Save updated score and badge
    }
}
