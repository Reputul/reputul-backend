package com.reputul.backend.dto.campaign.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateSequenceRequest {
    @Size(max = 255, message = "Name must be less than 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;

    private Boolean isDefault;
    private Boolean isActive;
}
