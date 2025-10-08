package com.reputul.backend.integrations;

public class PlatformIntegrationException extends Exception {

    public PlatformIntegrationException(String message) {
        super(message);
    }

    public PlatformIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}