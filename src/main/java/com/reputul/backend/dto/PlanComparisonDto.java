package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanComparisonDto {

    private String currentPlan;
    private Map<String, PlanDetailsDto> availablePlans;
    private List<String> recommendations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlanDetailsDto {
        private String name;
        private double price;
        private String description;
        private Map<String, Object> limits;
        private List<String> features;
        private boolean recommended;
        private String upgradeUrl;
    }
}