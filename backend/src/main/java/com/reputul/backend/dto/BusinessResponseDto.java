package com.reputul.backend.dto;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusinessResponseDto {
    private Long id;
    private String name;
    private String industry;
    private String phone;
    private String website;
    private String address;
    private Double reputationScore;
    private String badge;
    private int reviewCount;
    private Boolean reviewPlatformsConfigured;
}
