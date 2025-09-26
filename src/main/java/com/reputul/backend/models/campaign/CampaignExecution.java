package com.reputul.backend.models.campaign;

import com.reputul.backend.enums.ExecutionStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "campaign_executions")
public class CampaignExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "review_request_id", nullable = false)
    private Long reviewRequestId;

    @NotNull
    @Column(name = "sequence_id", nullable = false)
    private Long sequenceId;

    @Min(1)
    @Column(name = "current_step")
    private Integer currentStep = 1;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionStatus status = ExecutionStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("scheduledAt ASC")
    private List<CampaignStepExecution> stepExecutions = new ArrayList<>();

    // Constructors
    public CampaignExecution() {}

    public CampaignExecution(Long reviewRequestId, Long sequenceId) {
        this.reviewRequestId = reviewRequestId;
        this.sequenceId = sequenceId;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getReviewRequestId() {
        return reviewRequestId;
    }

    public void setReviewRequestId(Long reviewRequestId) {
        this.reviewRequestId = reviewRequestId;
    }

    public Long getSequenceId() {
        return sequenceId;
    }

    public void setSequenceId(Long sequenceId) {
        this.sequenceId = sequenceId;
    }

    public Integer getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(Integer currentStep) {
        this.currentStep = currentStep;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
        if (status.isFinished() && completedAt == null) {
            this.completedAt = LocalDateTime.now();
        }
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public List<CampaignStepExecution> getStepExecutions() {
        return stepExecutions;
    }

    public void setStepExecutions(List<CampaignStepExecution> stepExecutions) {
        this.stepExecutions = stepExecutions;
    }

    // Helper methods
    public void addStepExecution(CampaignStepExecution stepExecution) {
        stepExecutions.add(stepExecution);
        stepExecution.setExecution(this);
    }

    public boolean isActive() {
        return status.isActive();
    }

    public boolean isFinished() {
        return status.isFinished();
    }

    public void markCompleted() {
        setStatus(ExecutionStatus.COMPLETED);
    }

    public void markFailed() {
        setStatus(ExecutionStatus.FAILED);
    }

    public void markCancelled() {
        setStatus(ExecutionStatus.CANCELLED);
    }

    @Override
    public String toString() {
        return "CampaignExecution{" +
                "id=" + id +
                ", reviewRequestId=" + reviewRequestId +
                ", sequenceId=" + sequenceId +
                ", currentStep=" + currentStep +
                ", status=" + status +
                ", startedAt=" + startedAt +
                '}';
    }
}