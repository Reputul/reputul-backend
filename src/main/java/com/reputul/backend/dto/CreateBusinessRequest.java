package com.reputul.backend.dto;

import lombok.Data;
import jakarta.validation.constraints.*;

@Data
public class CreateBusinessRequest {
    @NotBlank(message = "Business name is required")
    private String name;

    @NotBlank(message = "Industry is required")
    private String industry;

    private String phone;
    private String website;
    private String address;
    private String city;
    private String state;
    private String zipCode;
    private String country;
}