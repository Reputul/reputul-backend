package com.reputul.backend.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "contacts")
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @NotBlank
    @Column(nullable = false)
    private String name;

    @Email
    @Column(unique = false) // Uniqueness handled by partial index
    private String email;

    @Column
    private String phone;

    @Column(name = "last_job_date")
    private LocalDate lastJobDate;

    // <-- FIX: map to PostgreSQL JSONB and tell Hibernate it's JSON
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags_json", columnDefinition = "jsonb")
    private String tagsJson;

    @Column(name = "sms_consent")
    private Boolean smsConsent;

    @Column(name = "email_consent")
    private Boolean emailConsent;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // Lazy-loaded business reference
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", insertable = false, updatable = false)
    @JsonIgnore
    private Business business;

    // Transient field for tags (converted from JSON)
    @Transient
    private Set<String> tags = new HashSet<>();

    // Constructors
    public Contact() {}

    public Contact(Long businessId, String name, String email, String phone, LocalDate lastJobDate) {
        this.businessId = businessId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.lastJobDate = lastJobDate;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        syncTagsToJson();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        syncTagsToJson();
    }

    @PostLoad
    protected void onLoad() {
        syncTagsFromJson();
    }

    // Convert tags Set to JSON string
    private void syncTagsToJson() {
        if (tags == null || tags.isEmpty()) {
            this.tagsJson = null;
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            this.tagsJson = mapper.writeValueAsString(tags);
        } catch (JsonProcessingException e) {
            // Log error but don't fail the operation
            this.tagsJson = null;
        }
    }

    // Convert JSON string to tags Set
    private void syncTagsFromJson() {
        if (tagsJson == null || tagsJson.trim().isEmpty()) {
            this.tags = new HashSet<>();
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            Set<String> parsedTags = mapper.readValue(tagsJson, new TypeReference<Set<String>>() {});
            this.tags = parsedTags != null ? parsedTags : new HashSet<>();
        } catch (JsonProcessingException e) {
            // Log error and use empty set
            this.tags = new HashSet<>();
        }
    }

    // Helper method to add tags
    public void addTag(String tag) {
        if (tag != null && !tag.trim().isEmpty()) {
            if (this.tags == null) {
                this.tags = new HashSet<>();
            }
            this.tags.add(tag.trim().toLowerCase());
        }
    }

    // Helper method to add multiple tags
    public void addTags(Set<String> newTags) {
        if (newTags != null) {
            if (this.tags == null) {
                this.tags = new HashSet<>();
            }
            for (String tag : newTags) {
                if (tag != null && !tag.trim().isEmpty()) {
                    this.tags.add(tag.trim().toLowerCase());
                }
            }
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) {
        // Normalize email: trim and lowercase, empty -> null
        if (email == null || email.trim().isEmpty()) {
            this.email = null;
        } else {
            this.email = email.trim().toLowerCase();
        }
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDate getLastJobDate() { return lastJobDate; }
    public void setLastJobDate(LocalDate lastJobDate) { this.lastJobDate = lastJobDate; }

    public Set<String> getTags() { return tags; }
    public void setTags(Set<String> tags) { this.tags = tags; }

    public Boolean getSmsConsent() { return smsConsent; }
    public void setSmsConsent(Boolean smsConsent) { this.smsConsent = smsConsent; }

    public Boolean getEmailConsent() { return emailConsent; }
    public void setEmailConsent(Boolean emailConsent) { this.emailConsent = emailConsent; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public Business getBusiness() { return business; }

    // For JSON serialization
    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
        syncTagsFromJson();
    }
}
