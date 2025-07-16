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

    public ReputationService(ReviewRepository reviewRepo, BusinessRepository businessRepo) {
        this.reviewRepo = reviewRepo;
        this.businessRepo = businessRepo;
    }

    public double getReputationScore(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) return 0.0;

        double average = reviews.stream()
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);

        double volumeWeight = Math.log10(reviews.size() + 1);

        return Math.round(average * volumeWeight * 10.0) / 10.0;
    }

    public String assignBadge(double score) {
        if (score >= 9.0) return "Top Rated";
        if (score >= 7.0) return "Excellent";
        if (score >= 5.0) return "Good";
        return "Needs Improvement";
    }

    public void updateBusinessReputationAndBadge(Long businessId) {
        Business business = businessRepo.findById(businessId).orElseThrow();

        double score = getReputationScore(businessId);
        String badge = assignBadge(score);

        business.setReputationScore(score);
        business.setBadge(badge);

        businessRepo.save(business);
    }
}
