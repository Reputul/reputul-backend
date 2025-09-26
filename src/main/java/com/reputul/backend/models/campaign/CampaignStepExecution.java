package com.reputul.backend.models.campaign;

import com.reputul.backend.enums.StepStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_step_executions")
public class CampaignStepExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_id", nullable = false)
    private CampaignExecution execution;

    @NotNull
    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @NotNull
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Constructors
    public CampaignStepExecution() {}

    public CampaignStepExecution(Long stepId, LocalDateTime scheduledAt) {
        this.stepId = stepId;
        this.scheduledAt = scheduledAt;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CampaignExecution getExecution() {
        return execution;
    }

    public void setExecution(CampaignExecution execution) {
        this.execution = execution;
    }

    public Long getStepId() {
        return stepId;
    }

    public void setStepId(Long stepId) {
        this.stepId = stepId;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
        if (status.isSuccessful() && sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // Helper methods
    public boolean isPending() {
        return status.isPending();
    }

    public boolean isSuccessful() {
        return status.isSuccessful();
    }

    public boolean hasFailed() {
        return status.isFailed();
    }

    public void markSent() {
        setStatus(StepStatus.SENT);
    }

    public void markDelivered() {
        setStatus(StepStatus.DELIVERED);
    }

    public void markFailed(String errorMessage) {
        setStatus(StepStatus.FAILED);
        setErrorMessage(errorMessage);
    }

    public void markSkipped() {
        setStatus(StepStatus.SKIPPED);
    }

    public boolean isDue() {
        return scheduledAt != null && LocalDateTime.now().isAfter(scheduledAt);
    }

    @Override
    public String toString() {
        return "CampaignStepExecution{" +
                "id=" + id +
                ", stepId=" + stepId +
                ", scheduledAt=" + scheduledAt +
                ", status=" + status +
                ", sentAt=" + sentAt +
                '}';
    }
}