package com.reputul.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WaitlistResponse {
    private boolean success;
    private String message;
    private boolean duplicate;
    private Integer waitlistCount;
    private LocalDateTime timestamp;
}