package com.reputul.backend.dto;

import lombok.Builder;
import lombok.Data;
import com.reputul.backend.models.Customer;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class CustomerFeedbackInfoDto {
    private CustomerInfoDto customer;
    private BusinessInfoDto business;

    @Data
    @Builder
    public static class CustomerInfoDto {
        private Long id;
        private String name;
        private String email;
        private String phone;
        private String serviceType;
        private LocalDate serviceDate;
        private Customer.CustomerStatus status;
        private List<Customer.CustomerTag> tags;
    }

    @Data
    @Builder
    public static class BusinessInfoDto {
        private Long id;
        private String name;
        private String industry;
        private String phone;
        private String website;
        private String address;
        private String googlePlaceId;
        private String facebookPageUrl;
        private String yelpPageUrl;
        private Boolean reviewPlatformsConfigured;
    }
}