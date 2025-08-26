package com.reputul.backend.dto;

import lombok.Data;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Data
public class FeedbackGateRequest {

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must be at most 5")
    private Integer rating;

    // Optional: Customer can provide initial comment at gate
    private String initialComment;

    // Track where this rating came from (email, sms, etc.)
    private String source;
}