package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;

@Service
public class BadgeService {

    private final ReviewRepository reviewRepo;

    public BadgeService(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    /**
     * Determines badge based on average rating and review volume.
     */
    public String determineBadge(double avgRating, int totalReviews) {
        if (totalReviews == 0) return "No Reviews Yet";
        if (avgRating >= 4.8 && totalReviews >= 10) return "Top Rated";
        if (avgRating >= 4.0 && totalReviews >= 5) return "Rising Star";
        return "Unranked";
    }

    /**
     * Calculates and updates the badge on a Business entity (but does not persist it).
     * @param business the business to update
     * @return the assigned badge
     */
    public String updateBusinessBadge(Business business) {
        Double avgRating = reviewRepo.findAverageRatingByBusinessId(business.getId());
        int totalReviews = reviewRepo.countByBusinessId(business.getId());

        double avg = avgRating != null ? avgRating : 0.0;
        String newBadge = determineBadge(avg, totalReviews);

        business.setBadge(newBadge);
        return newBadge;
    }
}
