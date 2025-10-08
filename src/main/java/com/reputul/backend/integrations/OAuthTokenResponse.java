package com.reputul.backend.integrations;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OAuthTokenResponse {
    private String accessToken;
    private String refreshToken;
    private Integer expiresIn; // seconds
    private String tokenType;
    private String scope;
}