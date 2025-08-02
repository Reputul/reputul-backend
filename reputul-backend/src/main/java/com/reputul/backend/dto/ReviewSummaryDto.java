package com.reputul.backend.dto;

public class ReviewSummaryDto {
    private double averageRating;
    private int totalReviews;
    private String mostRecentComment;
    private String badge;

    public ReviewSummaryDto(double averageRating, int totalReviews, String mostRecentComment, String badge) {
        this.averageRating = averageRating;
        this.totalReviews = totalReviews;
        this.mostRecentComment = mostRecentComment;
        this.badge = badge;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public int getTotalReviews() {
        return totalReviews;
    }

    public String getMostRecentComment() {
        return mostRecentComment;
    }

    public String getBadge() {
        return badge;
    }

    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public void setTotalReviews(int totalReviews) {
        this.totalReviews = totalReviews;
    }

    public void setMostRecentComment(String mostRecentComment) {
        this.mostRecentComment = mostRecentComment;
    }

    public void setBadge(String badge) {
        this.badge = badge;
    }
}
