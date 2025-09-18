package com.reputul.backend.models.automation;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "automation_workflow_steps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationWorkflowStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "workflow_id", nullable = false)
    private Long workflowId;

    @Min(1)
    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @NotBlank
    @Size(max = 50)
    @Column(name = "step_type", nullable = false, length = 50)
    private String stepType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> stepConfig;

    @Size(max = 50)
    @Column(name = "condition_type", length = 50)
    private String conditionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_data", columnDefinition = "jsonb")
    private Map<String, Object> conditionData;

    @Column(name = "success_next_step")
    private Integer successNextStep;

    @Column(name = "failure_next_step")
    private Integer failureNextStep;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}