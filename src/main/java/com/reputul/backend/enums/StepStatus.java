package com.reputul.backend.enums;

public enum StepStatus {
    PENDING("Pending"),
    SENT("Sent"),
    DELIVERED("Delivered"),
    FAILED("Failed"),
    SKIPPED("Skipped");

    private final String displayName;

    StepStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSuccessful() {
        return this == SENT || this == DELIVERED;
    }

    public boolean isFailed() {
        return this == FAILED;
    }

    public boolean isPending() {
        return this == PENDING;
    }
}