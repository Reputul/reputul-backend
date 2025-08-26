package com.reputul.backend.dto;

import java.util.List;
import java.util.Map;

public class CsvImportCommitResponse {

    private String status;
    private int totalProcessed;
    private int insertedCount;
    private int updatedCount;
    private int skippedCount;
    private int errorCount;
    private List<Map<String, Object>> sampleErrors;
    private Long importJobId;

    // Constructors
    public CsvImportCommitResponse() {}

    public CsvImportCommitResponse(String status, int totalProcessed) {
        this.status = status;
        this.totalProcessed = totalProcessed;
    }

    // Getters and Setters
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getTotalProcessed() { return totalProcessed; }
    public void setTotalProcessed(int totalProcessed) { this.totalProcessed = totalProcessed; }

    public int getInsertedCount() { return insertedCount; }
    public void setInsertedCount(int insertedCount) { this.insertedCount = insertedCount; }

    public int getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(int updatedCount) { this.updatedCount = updatedCount; }

    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }

    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

    public List<Map<String, Object>> getSampleErrors() { return sampleErrors; }
    public void setSampleErrors(List<Map<String, Object>> sampleErrors) { this.sampleErrors = sampleErrors; }

    public Long getImportJobId() { return importJobId; }
    public void setImportJobId(Long importJobId) { this.importJobId = importJobId; }
}