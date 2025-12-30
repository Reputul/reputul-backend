package com.reputul.platform.dto.integration;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for Zapier webhook to create/update a contact
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ZapierContactRequest {

    @NotBlank(message = "Customer name is required")
    @Size(max = 255, message = "Name must not exceed 255 characters")
    @JsonProperty("customer_name")
    private String customerName;

    @Email(message = "Invalid email format")
    @JsonProperty("email")
    private String email;

    @Size(max = 50, message = "Phone must not exceed 50 characters")
    @JsonProperty("phone")
    private String phone;

    @JsonProperty("business_id")
    private UUID businessId;

    @JsonProperty("job_id")
    private String jobId; // External reference from source system

    @JsonProperty("job_completed_at")
    private String jobCompletedAt; // ISO timestamp

    @JsonProperty("notes")
    private String notes;

    /**
     * Validates that at least email or phone is provided
     */
    public boolean hasContactMethod() {
        return (email != null && !email.trim().isEmpty()) ||
                (phone != null && !phone.trim().isEmpty());
    }
}