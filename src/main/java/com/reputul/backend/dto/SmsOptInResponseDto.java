package com.reputul.backend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SmsOptInResponseDto {

    private boolean success;
    private String message;
    private boolean requiresConfirmation;
    private Long customerId;
    private String confirmationCode; // For tracking double opt-in

    // Additional context for frontend
    private String businessName;
    private String nextStep;
}