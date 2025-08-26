package com.reputul.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

public class ContactDto {
    private Long id;
    private Long businessId;
    private String name;
    private String email;
    private String phone;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate lastJobDate;

    private Set<String> tags;
    private Boolean smsConsent;
    private Boolean emailConsent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // Constructors
    public ContactDto() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}