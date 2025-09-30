package com.reputul.backend.dto.campaign.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateSequenceRequest {
    @NotBlank(message = "Sequence name is required")
    @Size(max = 255, message = "Name must be less than 255 characters")
    private String name;

    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;

    private Boolean isDefault = false;

    private List<CreateStepRequest> steps;
}
