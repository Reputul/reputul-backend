package com.reputul.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateReplyResponse {
    private String reply;
    private String tone; // "professional", "friendly", "apologetic"
    private Long reviewId;
}