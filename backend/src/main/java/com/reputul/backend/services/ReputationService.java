package com.reputul.backend.services;

import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.ReviewRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReputationService {

    private final ReviewRepository reviewRepo;

    public ReputationService(ReviewRepository reviewRepo) {
        this.reviewRepo = reviewRepo;
    }

    public double getReputationScore(Long businessId) {
        List<Review> reviews = reviewRepo.findByBusinessId(businessId);

        if (reviews.isEmpty()) return 0.0;

        double average = reviews.stream()
                .mapToInt(Review::getRating)
                .average()
                .orElse(0.0);

        double volumeWeight = Math.log10(reviews.size() + 1);

        return Math.round(average * volumeWeight * 10.0) / 10.0;  // rounded to 1 decimal
    }
}
