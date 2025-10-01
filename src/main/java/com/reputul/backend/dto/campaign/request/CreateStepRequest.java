package com.reputul.backend.dto.campaign.request;

import com.reputul.backend.enums.MessageType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateStepRequest {
    @NotNull(message = "Step number is required")
    @Min(value = 1, message = "Step number must be positive")
    private Integer stepNumber;

    @NotNull(message = "Delay hours is required")
    @Min(value = 0, message = "Delay hours cannot be negative")
    private Integer delayHours;

    @NotNull(message = "Message type is required")
    private MessageType messageType;

    @Size(max = 255, message = "Subject template must be less than 255 characters")
    private String subjectTemplate;

    @NotBlank(message = "Body template is required")
    private String bodyTemplate;
}