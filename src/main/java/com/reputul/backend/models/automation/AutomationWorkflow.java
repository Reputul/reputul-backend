package com.reputul.backend.models.automation;

import com.reputul.backend.models.Organization;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import com.reputul.backend.models.EmailTemplate;
import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@Table(name = "automation_workflows")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationWorkflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id")
    private Business business;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 50)
    private TriggerType triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trigger_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> triggerConfig;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @Min(1)
    @Column(name = "max_executions")
    private Integer maxExecutions;

    @Min(0)
    @Column(name = "execution_count")
    @Builder.Default
    private Integer executionCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", length = 20)
    @Builder.Default
    private DeliveryMethod deliveryMethod = DeliveryMethod.EMAIL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_template_id")
    private EmailTemplate emailTemplate;

    @Column(name = "sms_template", columnDefinition = "TEXT")
    private String smsTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_window", columnDefinition = "jsonb")
    private Map<String, Object> executionWindow;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", columnDefinition = "jsonb")
    private Map<String, Object> conditions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actions", columnDefinition = "jsonb")
    private Map<String, Object> actions;

    @CreationTimestamp
    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    public enum TriggerType {
        TIME_BASED,
        EVENT_BASED,
        MANUAL,
        WEBHOOK,
        CUSTOMER_CREATED,
        SERVICE_COMPLETED,
        REVIEW_COMPLETED,
        SCHEDULED
    }

    public enum DeliveryMethod {
        EMAIL,
        SMS,
        BOTH
    }
}