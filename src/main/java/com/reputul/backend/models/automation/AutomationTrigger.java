package com.reputul.backend.models.automation;

import com.reputul.backend.models.Organization;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "automation_triggers")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private AutomationWorkflow workflow;

    @NotBlank
    @Size(max = 100)
    @Column(name = "trigger_name", nullable = false, length = 100)
    private String triggerName;

    @NotBlank
    @Size(max = 50)
    @Column(name = "trigger_type", nullable = false, length = 50)
    private String triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @Size(max = 100)
    @Column(name = "event_type", length = 100)
    private String eventType;

    @Size(max = 100)
    @Column(name = "event_source", length = 100)
    private String eventSource;

    @Size(max = 100)
    @Column(name = "schedule_expression", length = 100)
    private String scheduleExpression;

    @Size(max = 50)
    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "last_triggered_at")
    private OffsetDateTime lastTriggeredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_config", columnDefinition = "jsonb")
    private Map<String, Object> triggerConfig;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}