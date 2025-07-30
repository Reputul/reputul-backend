package com.reputul.backend.dto;

import lombok.Data;

@Data
public class SubmitFeedbackRequest {
    private Integer rating;
    private String comment;
    private String type;
}