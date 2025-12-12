package com.reputul.backend.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

/**
 * Request DTO for creating a new business
 *
 * UPDATED: Supports optional manual Google Place ID and g.page URL overrides
 */
@Data
public class CreateBusinessRequest {
    @NotBlank(message = "Business name is required")
    private String name;

    @NotBlank(message = "Industry is required")
    private String industry;

    private String phone;
    private String website;

    @NotBlank(message = "Address is required for Google Places auto-detection")
    private String address;

    private String city;
    private String state;
    private String zipCode;
    private String country;

    // ===== NEW: Optional manual Google overrides =====

    /**
     * Optional: Manually provided Google Place ID
     * If not provided, system will auto-detect using Places API
     */
    private String googlePlaceId;

    /**
     * Optional: User-provided g.page short URL
     * Example: https://g.page/r/CZfH8POGJQGsEAI/review
     */
    private String googleReviewShortUrl;
}