package com.reputul.backend.exceptions;

/**
 * Exception thrown when a platform's access token has expired and cannot be refreshed.
 * This signals the frontend to prompt the user to reconnect the platform.
 */
public class TokenExpiredException extends RuntimeException {

    private final String platformType;
    private final Long credentialId;
    private final boolean canReconnect;

    public TokenExpiredException(String platformType, Long credentialId, String message) {
        super(message);
        this.platformType = platformType;
        this.credentialId = credentialId;
        this.canReconnect = true;
    }

    public TokenExpiredException(String platformType, Long credentialId, String message, Throwable cause) {
        super(message, cause);
        this.platformType = platformType;
        this.credentialId = credentialId;
        this.canReconnect = true;
    }

    public String getPlatformType() {
        return platformType;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public boolean canReconnect() {
        return canReconnect;
    }
}