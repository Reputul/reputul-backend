package com.reputul.backend.dto;

public class ResetPasswordRequestDto {
    private String token;
    private String newPassword;

    // Constructors, getters, setters
    public ResetPasswordRequestDto() {}

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getNewPassword() { return newPassword; }
    public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
}
