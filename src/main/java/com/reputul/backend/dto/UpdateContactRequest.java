package com.reputul.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.Set;

public class UpdateContactRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @Email(message = "Invalid email format")
    private String email;

    private String phone;
    private LocalDate lastJobDate;
    private Set<String> tags;
    private Boolean smsConsent;
    private Boolean emailConsent;

    // Getters and Setters
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
}