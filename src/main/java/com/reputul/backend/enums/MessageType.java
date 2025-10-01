package com.reputul.backend.enums;

public enum MessageType {
    SMS("SMS"),
    EMAIL_PROFESSIONAL("Professional Email"),
    EMAIL_PLAIN("Plain Email");

    private final String displayName;

    MessageType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEmail() {
        return this == EMAIL_PROFESSIONAL || this == EMAIL_PLAIN;
    }

    public boolean isSms() {
        return this == SMS;
    }
}