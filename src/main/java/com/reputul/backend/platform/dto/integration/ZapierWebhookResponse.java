package com.reputul.backend.platform.dto.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response DTO for Zapier webhook requests
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ZapierWebhookResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("contact")
    private ContactInfo contact;

    @JsonProperty("review_request")
    private ReviewRequestInfo reviewRequest;

    @JsonProperty("error")
    private ErrorInfo error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContactInfo {
        @JsonProperty("id")
        private Long id;  // FIXED: Changed from UUID to Long

        @JsonProperty("created")
        private boolean created; // true if new, false if updated

        @JsonProperty("email")
        private String email;

        @JsonProperty("phone")
        private String phone;

        @JsonProperty("name")
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReviewRequestInfo {
        @JsonProperty("id")
        private Long id;  // FIXED: Changed from UUID to Long

        @JsonProperty("status")
        private String status;

        @JsonProperty("scheduled_send_at")
        private LocalDateTime scheduledSendAt;

        @JsonProperty("delivery_method")
        private String deliveryMethod;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorInfo {
        @JsonProperty("code")
        private String code;

        @JsonProperty("message")
        private String message;

        @JsonProperty("retry_after")
        private LocalDateTime retryAfter;
    }

    /**
     * Creates a success response for contact creation
     */
    public static ZapierWebhookResponse contactSuccess(Long contactId, boolean created, String email, String phone, String name) {
        return ZapierWebhookResponse.builder()
                .success(true)
                .message(created ? "Contact created successfully" : "Contact updated successfully")
                .contact(ContactInfo.builder()
                        .id(contactId)
                        .created(created)
                        .email(email)
                        .phone(phone)
                        .name(name)
                        .build())
                .build();
    }

    /**
     * Creates a success response for review request
     */
    public static ZapierWebhookResponse reviewRequestSuccess(
            Long contactId, boolean contactCreated, String email, String phone, String name,
            Long requestId, String status, LocalDateTime scheduledSendAt, String deliveryMethod) {
        return ZapierWebhookResponse.builder()
                .success(true)
                .message("Contact " + (contactCreated ? "created" : "updated") + " and review request scheduled")
                .contact(ContactInfo.builder()
                        .id(contactId)
                        .created(contactCreated)
                        .email(email)
                        .phone(phone)
                        .name(name)
                        .build())
                .reviewRequest(ReviewRequestInfo.builder()
                        .id(requestId)
                        .status(status)
                        .scheduledSendAt(scheduledSendAt)
                        .deliveryMethod(deliveryMethod)
                        .build())
                .build();
    }

    /**
     * Creates an error response
     */
    public static ZapierWebhookResponse error(String code, String message, LocalDateTime retryAfter) {
        return ZapierWebhookResponse.builder()
                .success(false)
                .error(ErrorInfo.builder()
                        .code(code)
                        .message(message)
                        .retryAfter(retryAfter)
                        .build())
                .build();
    }

    /**
     * Creates an error response without retry_after
     */
    public static ZapierWebhookResponse error(String code, String message) {
        return error(code, message, null);
    }
}