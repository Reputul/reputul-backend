package com.reputul.backend.models.campaign;

import com.reputul.backend.enums.MessageType;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "campaign_steps")
public class CampaignStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sequence_id", nullable = false)
    private CampaignSequence sequence;

    @NotNull
    @Min(1)
    @Column(name = "step_number", nullable = false)
    private Integer stepNumber;

    @NotNull
    @Min(0)
    @Column(name = "delay_hours", nullable = false)
    private Integer delayHours = 0;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Size(max = 255)
    @Column(name = "subject_template")
    private String subjectTemplate;

    @NotBlank
    @Column(name = "body_template", nullable = false, columnDefinition = "TEXT")
    private String bodyTemplate;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    // Constructors
    public CampaignStep() {}

    public CampaignStep(Integer stepNumber, Integer delayHours, MessageType messageType, String bodyTemplate) {
        this.stepNumber = stepNumber;
        this.delayHours = delayHours;
        this.messageType = messageType;
        this.bodyTemplate = bodyTemplate;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public CampaignSequence getSequence() {
        return sequence;
    }

    public void setSequence(CampaignSequence sequence) {
        this.sequence = sequence;
    }

    public Integer getStepNumber() {
        return stepNumber;
    }

    public void setStepNumber(Integer stepNumber) {
        this.stepNumber = stepNumber;
    }

    public Integer getDelayHours() {
        return delayHours;
    }

    public void setDelayHours(Integer delayHours) {
        this.delayHours = delayHours;
    }

    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    public String getSubjectTemplate() {
        return subjectTemplate;
    }

    public void setSubjectTemplate(String subjectTemplate) {
        this.subjectTemplate = subjectTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    // Helper methods
    public boolean requiresSubject() {
        return messageType != null && messageType.isEmail();
    }

    public String getDelayDescription() {
        if (delayHours == null || delayHours == 0) {
            return "Immediately";
        } else if (delayHours < 24) {
            return delayHours + " hour" + (delayHours > 1 ? "s" : "");
        } else {
            int days = delayHours / 24;
            return days + " day" + (days > 1 ? "s" : "");
        }
    }

    @Override
    public String toString() {
        return "CampaignStep{" +
                "id=" + id +
                ", stepNumber=" + stepNumber +
                ", delayHours=" + delayHours +
                ", messageType=" + messageType +
                ", isActive=" + isActive +
                '}';
    }
}