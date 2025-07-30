package com.reputul.backend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeedbackResponse {
    private Long id;
    private String message;
    private Boolean success;
}