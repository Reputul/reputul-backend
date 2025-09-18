package com.reputul.backend.models.automation;

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
@Table(name = "workflow_templates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @NotBlank
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @NotBlank
    @Size(max = 50)
    @Column(nullable = false, length = 50)
    private String category;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 50)
    private AutomationWorkflow.TriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> templateConfig;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_actions", columnDefinition = "jsonb")
    private Map<String, Object> defaultActions;

    @Column(name = "is_system_template")
    @Builder.Default
    private Boolean isSystemTemplate = false;

    @Column(name = "popularity_score")
    @Builder.Default
    private Integer popularityScore = 0;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}