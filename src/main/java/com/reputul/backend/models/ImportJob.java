package com.reputul.backend.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "import_jobs")
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column
    private String filename;

    @Column(name = "total_rows")
    private Integer totalRows = 0;

    @Column(name = "inserted_count")
    private Integer insertedCount = 0;

    @Column(name = "updated_count")
    private Integer updatedCount = 0;

    @Column(name = "skipped_count")
    private Integer skippedCount = 0;

    @Column(name = "error_count")
    private Integer errorCount = 0;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(name = "error_details", columnDefinition = "JSON")
    private String errorDetailsJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Transient field for error details
    @Transient
    private List<Map<String, Object>> errorDetails;

    public enum Status {
        PENDING, COMPLETED, FAILED
    }

    public ImportJob() {}

    public ImportJob(Long businessId, Long userId, String filename) {
        this.businessId = businessId;
        this.userId = userId;
        this.filename = filename;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        syncErrorDetailsToJson();
    }

    @PreUpdate
    protected void onUpdate() {
        syncErrorDetailsToJson();
    }

    @PostLoad
    protected void onLoad() {
        syncErrorDetailsFromJson();
    }

    private void syncErrorDetailsToJson() {
        if (errorDetails == null || errorDetails.isEmpty()) {
            this.errorDetailsJson = null;
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            this.errorDetailsJson = mapper.writeValueAsString(errorDetails);
        } catch (JsonProcessingException e) {
            this.errorDetailsJson = null;
        }
    }

    private void syncErrorDetailsFromJson() {
        if (errorDetailsJson == null || errorDetailsJson.trim().isEmpty()) {
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            this.errorDetails = mapper.readValue(errorDetailsJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            // Ignore parsing errors
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public Integer getTotalRows() { return totalRows; }
    public void setTotalRows(Integer totalRows) { this.totalRows = totalRows; }

    public Integer getInsertedCount() { return insertedCount; }
    public void setInsertedCount(Integer insertedCount) { this.insertedCount = insertedCount; }

    public Integer getUpdatedCount() { return updatedCount; }
    public void setUpdatedCount(Integer updatedCount) { this.updatedCount = updatedCount; }

    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }

    public Integer getErrorCount() { return errorCount; }
    public void setErrorCount(Integer errorCount) { this.errorCount = errorCount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public List<Map<String, Object>> getErrorDetails() { return errorDetails; }
    public void setErrorDetails(List<Map<String, Object>> errorDetails) { this.errorDetails = errorDetails; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}