package com.reputul.backend.controllers;

import com.reputul.backend.dto.BusinessResponseDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.Review;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.ReviewRepository;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final BusinessRepository businessRepo;
    private final UserRepository userRepo;
    private final ReviewRepository reviewRepo;
    private final ReviewRequestRepository reviewRequestRepo;

    public DashboardController(BusinessRepository businessRepo,
                               UserRepository userRepo,
                               ReviewRepository reviewRepo,
                               ReviewRequestRepository reviewRequestRepo) {
        this.businessRepo = businessRepo;
        this.userRepo = userRepo;
        this.reviewRepo = reviewRepo;
        this.reviewRequestRepo = reviewRequestRepo;
    }

    @GetMapping
    public List<BusinessResponseDto> getUserBusinesses(@AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails.getUsername();
        User user = userRepo.findByEmail(email).orElseThrow();

        List<Business> businesses = businessRepo.findByUserId(user.getId());

        return businesses.stream().map(b -> BusinessResponseDto.builder()
                .id(b.getId())
                .name(b.getName())
                .industry(b.getIndustry())
                .phone(b.getPhone())
                .website(b.getWebsite())
                .address(b.getAddress())
                .reputationScore(b.getReputationScore())
                .badge(b.getBadge())
                .reviewCount(Math.toIntExact(reviewRepo.countByBusinessId(b.getId())))
                .reviewPlatformsConfigured(b.getReviewPlatformsConfigured())
                .build()
        ).toList();
    }

    /**
     * NEW: Dashboard metrics endpoint with KPIs and time series data
     * GET /api/dashboard/metrics?days=30
     */
    @GetMapping("/metrics")
    public Map<String, Object> getDashboardMetrics(
            @RequestParam(defaultValue = "30") int days,
            @AuthenticationPrincipal UserDetails userDetails) {

        String email = userDetails.getUsername();
        User user = userRepo.findByEmail(email).orElseThrow();

        OffsetDateTime cutoffDate = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days);

        // Get user's businesses for filtering
        List<Business> userBusinesses = businessRepo.findByUserId(user.getId());
        Set<Long> businessIds = userBusinesses.stream()
                .map(Business::getId)
                .collect(Collectors.toSet());

        // If no businesses, return empty metrics
        if (businessIds.isEmpty()) {
            return createEmptyMetrics(days);
        }

        // Calculate KPIs using existing repository methods
        Map<String, Object> metrics = new HashMap<>();

        // Review Request Metrics (sent, delivered, failed, completed)
        calculateReviewRequestMetrics(user.getId(), cutoffDate, metrics);

        // Time series data for the last X days
        calculateTimeSeriesData(user.getId(), businessIds, days, metrics);

        // Additional summary metrics
        calculateSummaryMetrics(user.getId(), businessIds, cutoffDate, metrics);

        return metrics;
    }

    private void calculateReviewRequestMetrics(Long userId, OffsetDateTime since, Map<String, Object> metrics) {
        // Use existing analytics method from ReviewRequestRepository
        Object[] analytics = reviewRequestRepo.getSmsAnalyticsByUserId(userId);

        if (analytics != null && analytics.length >= 5) {
            metrics.put("sent", ((Number) analytics[1]).intValue());
            metrics.put("delivered", ((Number) analytics[2]).intValue());
            metrics.put("failed", ((Number) analytics[3]).intValue());
            metrics.put("completed", ((Number) analytics[4]).intValue());
        } else {
            // Fallback if no SMS data - get all review requests
            List<ReviewRequest> requests = reviewRequestRepo.findByOwnerId(userId);

            int sent = 0, delivered = 0, failed = 0, completed = 0;

            for (ReviewRequest request : requests) {
                ReviewRequest.RequestStatus status = request.getStatus();
                if (status != null) {
                    switch (status) {
                        case SENT -> sent++;
                        case DELIVERED -> delivered++;
                        case FAILED -> failed++;
                        case COMPLETED -> completed++;
                    }
                }
            }

            metrics.put("sent", sent);
            metrics.put("delivered", delivered);
            metrics.put("failed", failed);
            metrics.put("completed", completed);
        }
    }

    private void calculateTimeSeriesData(Long userId, Set<Long> businessIds, int days, Map<String, Object> metrics) {
        List<Map<String, Object>> byDay = new ArrayList<>();

        for (int i = days - 1; i >= 0; i--) {
            OffsetDateTime dayStart = OffsetDateTime.now(ZoneOffset.UTC)
                    .minusDays(i)
                    .withHour(0)
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0);
            OffsetDateTime dayEnd = dayStart.plusDays(1);

            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", dayStart.format(DateTimeFormatter.ISO_LOCAL_DATE));

            // Count review requests sent on this day
            List<ReviewRequest> dayRequests =
                    reviewRequestRepo.findByOwnerId(userId).stream()
                            .filter(r -> r.getCreatedAt() != null &&
                                    r.getCreatedAt().isAfter(dayStart) &&
                                    r.getCreatedAt().isBefore(dayEnd))
                            .toList();

            dayData.put("requestsSent", dayRequests.size());

            // Count reviews received on this day (across user's businesses)
            int reviewsReceived = 0;
            for (Long businessId : businessIds) {
                List<Review> dayReviews =
                        reviewRepo.findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(businessId, dayStart).stream()
                                .filter(r -> r.getCreatedAt().isBefore(dayEnd))
                                .toList();
                reviewsReceived += dayReviews.size();
            }
            dayData.put("reviewsReceived", reviewsReceived);

            // Calculate completion rate for requests sent this day
            long completedRequests = dayRequests.stream()
                    .filter(r -> ReviewRequest.RequestStatus.COMPLETED.equals(r.getStatus()))
                    .count();

            double completionRate = dayRequests.isEmpty() ? 0.0 :
                    (double) completedRequests / dayRequests.size() * 100;
            dayData.put("completionRate", Math.round(completionRate * 10.0) / 10.0);

            byDay.add(dayData);
        }

        metrics.put("byDay", byDay);
    }

    private void calculateSummaryMetrics(Long userId, Set<Long> businessIds, OffsetDateTime since, Map<String, Object> metrics) {
        // Total reviews across all businesses in period
        int totalReviewsInPeriod = 0;
        double totalRating = 0.0;
        int ratedReviews = 0;

        for (Long businessId : businessIds) {
            List<Review> businessReviews =
                    reviewRepo.findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(businessId, since);

            totalReviewsInPeriod += businessReviews.size();

            // Since rating is primitive int, it's never null - always add it
            for (Review review : businessReviews) {
                totalRating += review.getRating();
                ratedReviews++;
            }
        }

        metrics.put("totalReviewsInPeriod", totalReviewsInPeriod);
        metrics.put("averageRatingInPeriod", ratedReviews > 0 ?
                Math.round((totalRating / ratedReviews) * 10.0) / 10.0 : 0.0);

        // Active businesses (businesses that received reviews in period)
        long activeBusinesses = businessIds.stream()
                .filter(businessId -> {
                    List<Review> recentReviews =
                            reviewRepo.findByBusinessIdAndCreatedAtAfterOrderByCreatedAtDesc(businessId, since);
                    return !recentReviews.isEmpty();
                })
                .count();

        metrics.put("activeBusinesses", (int) activeBusinesses);
        metrics.put("totalBusinesses", businessIds.size());
    }

    private Map<String, Object> createEmptyMetrics(int days) {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sent", 0);
        metrics.put("delivered", 0);
        metrics.put("failed", 0);
        metrics.put("completed", 0);
        metrics.put("totalReviewsInPeriod", 0);
        metrics.put("averageRatingInPeriod", 0.0);
        metrics.put("activeBusinesses", 0);
        metrics.put("totalBusinesses", 0);

        // Empty time series
        List<Map<String, Object>> byDay = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            OffsetDateTime day = OffsetDateTime.now(ZoneOffset.UTC).minusDays(i);
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", day.format(DateTimeFormatter.ISO_LOCAL_DATE));
            dayData.put("requestsSent", 0);
            dayData.put("reviewsReceived", 0);
            dayData.put("completionRate", 0.0);
            byDay.add(dayData);
        }
        metrics.put("byDay", byDay);

        return metrics;
    }
}