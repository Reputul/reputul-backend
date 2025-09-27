package com.reputul.backend.enums;

public enum SourceTrigger {
    MANUAL("Manual"),
    CRM_INTEGRATION("CRM Integration"),
    API("API"),
    BULK_IMPORT("Bulk Import");

    private final String displayName;

    SourceTrigger(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAutomated() {
        return this == CRM_INTEGRATION || this == API;
    }
}