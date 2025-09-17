package com.reputul.backend.models.automation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "step_type", nullable = false, length = 50)
    private String stepType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "step_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> stepConfig;

    @Column(name = "condition_type", length = 50)
    private String conditionType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_data", columnDefinition = "jsonb")
    private Map<String, Object> conditionData;

    @Column(name = "success_next_step")
    private Integer successNextStep;

    @Column(name = "failure_next_step")
    private Integer failureNextStep;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}