package com.reputul.backend.dto;

import com.reputul.backend.models.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerDto {
    private Long id;
    private String name;
    private String email;
    private String phone;
    private LocalDate serviceDate;
    private String serviceType;
    private Customer.CustomerStatus status;
    private List<Customer.CustomerTag> tags;
    private String notes;
    private BusinessInfo business;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessInfo {
        private Long id;
        private String name;
        private String industry;
    }
}
