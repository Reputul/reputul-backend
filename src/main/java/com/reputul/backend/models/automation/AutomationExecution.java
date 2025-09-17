package com.reputul.backend.models.automation;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "automation_executions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private AutomationWorkflow workflow;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "trigger_event", nullable = false, length = 100)
    private String triggerEvent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_data", columnDefinition = "jsonb")
    private Map<String, Object> triggerData;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    @Builder.Default
    private ExecutionStatus status = ExecutionStatus.PENDING;

    @Min(1)
    @Column(name = "current_step")
    @Builder.Default
    private Integer currentStep = 1;

    @Column(name = "scheduled_for")
    private OffsetDateTime scheduledFor;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "next_execution_at")
    private OffsetDateTime nextExecutionAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "results", columnDefinition = "jsonb")
    private Map<String, Object> results;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_data", columnDefinition = "jsonb")
    private Map<String, Object> executionData;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Min(0)
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Min(0) @Max(10)
    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        PAUSED,
        CANCELLED
    }
}