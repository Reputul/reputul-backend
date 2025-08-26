package com.reputul.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public class CsvImportCommitRequest {

    @NotBlank(message = "Upload ID is required")
    private String uploadId;

    @NotNull(message = "Column mapping is required")
    private Map<String, String> columnMap;

    @NotBlank(message = "Import mode is required")
    private String mode;  // "upsert" or "skip-duplicates"

    private String dedupeStrategy;  // "email_phone_name_date"

    private List<String> defaultTags;

    // Getters and Setters
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }

    public Map<String, String> getColumnMap() { return columnMap; }
    public void setColumnMap(Map<String, String> columnMap) { this.columnMap = columnMap; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getDedupeStrategy() { return dedupeStrategy; }
    public void setDedupeStrategy(String dedupeStrategy) { this.dedupeStrategy = dedupeStrategy; }

    public List<String> getDefaultTags() { return defaultTags; }
    public void setDefaultTags(List<String> defaultTags) { this.defaultTags = defaultTags; }
}