package com.reputul.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleConnectionDto {

    @NotBlank(message = "Google Maps URL is required")
    private String googleMapsUrl;
}