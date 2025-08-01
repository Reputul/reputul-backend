package com.reputul.backend.dto;

import com.reputul.backend.models.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

// Request DTO for creating/updating customers
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCustomerRequest {
    private String name;
    private String email;
    private String phone;
    private LocalDate serviceDate;
    private String serviceType;
    private Customer.CustomerStatus status;
    private List<Customer.CustomerTag> tags;
    private String notes;
    private Long businessId;
}
