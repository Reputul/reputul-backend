package com.reputul.backend.models.automation;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "customer_workflow_states",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "workflow_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerWorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Min(0)
    @Column(name = "current_step")
    @Builder.Default
    private Integer currentStep = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private StateStatus status = StateStatus.ELIGIBLE;

    @Column(name = "last_executed_step")
    private Integer lastExecutedStep;

    @Column(name = "last_execution_at")
    private OffsetDateTime lastExecutionAt;

    @Min(0)
    @Column(name = "completion_count")
    @Builder.Default
    private Integer completionCount = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state_data", columnDefinition = "jsonb")
    private Map<String, Object> stateData;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public enum StateStatus {
        ELIGIBLE, ACTIVE, COMPLETED, PAUSED, EXCLUDED
    }
}