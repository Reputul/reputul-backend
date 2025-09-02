package com.reputul.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SmsOptInRequestDto {

    @NotNull(message = "Business ID is required")
    private Long businessId;

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[+]?[1-9]\\d{1,14}$", message = "Invalid phone number format")
    private String phone;

    @Email(message = "Invalid email format")
    private String email; // Optional

    private String serviceType; // Optional - defaults to "SMS_SIGNUP"

    // TCPA compliance fields
    private boolean agreedToTerms;
    private boolean agreedToSms;
    private String referralSource; // How they found out about SMS signup
}