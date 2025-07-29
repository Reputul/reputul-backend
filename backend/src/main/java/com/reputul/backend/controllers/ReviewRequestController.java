package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.ReviewRequestService;
import com.reputul.backend.services.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review-requests")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
public class ReviewRequestController {

    private final ReviewRequestService reviewRequestService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    @PostMapping("")
    public ResponseEntity<ReviewRequestDto> sendReviewRequest(
            @RequestBody SendReviewRequestDto request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            ReviewRequestDto result = reviewRequestService.sendReviewRequest(user, request);
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ReviewRequestDto>> sendBulkReviewRequests(
            @RequestBody SendBulkReviewRequestDto request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<ReviewRequestDto> results = request.getCustomerIds().stream()
                    .map(customerId -> {
                        SendReviewRequestDto singleRequest = SendReviewRequestDto.builder()
                                .customerId(customerId)
                                .templateId(request.getTemplateId())
                                .notes(request.getNotes())
                                .build();
                        return reviewRequestService.sendReviewRequest(user, singleRequest);
                    })
                    .toList();
            return ResponseEntity.ok(results);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("")
    public ResponseEntity<List<ReviewRequestDto>> getAllReviewRequests(Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<ReviewRequestDto> requests = reviewRequestService.getAllReviewRequestsByUser(user);
        return ResponseEntity.ok(requests);
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<ReviewRequestDto>> getReviewRequestsByBusiness(
            @PathVariable Long businessId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<ReviewRequestDto> requests = reviewRequestService.getReviewRequestsByBusiness(user, businessId);
            return ResponseEntity.ok(requests);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{requestId}/status")
    public ResponseEntity<ReviewRequestDto> updateRequestStatus(
            @PathVariable Long requestId,
            @RequestBody Map<String, String> statusUpdate) {
        try {
            ReviewRequest.RequestStatus status = ReviewRequest.RequestStatus.valueOf(statusUpdate.get("status"));
            ReviewRequestDto updated = reviewRequestService.updateStatus(requestId, status);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTestEmail(
            @RequestBody Map<String, String> testRequest,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            String toEmail = testRequest.get("email");
            String subject = testRequest.getOrDefault("subject", "Test Email from Reputul");
            String body = testRequest.getOrDefault("body", "This is a test email to verify your SendGrid integration is working properly.");

            boolean sent = emailService.sendTestEmail(toEmail, subject, body);

            return ResponseEntity.ok(Map.of(
                    "success", sent,
                    "message", sent ? "Test email sent successfully!" : "Failed to send test email"
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Error: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getReviewRequestStats(Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<ReviewRequestDto> allRequests = reviewRequestService.getAllReviewRequestsByUser(user);

        long totalSent = allRequests.stream().filter(r -> r.getStatus() != ReviewRequest.RequestStatus.PENDING).count();
        long successful = allRequests.stream().filter(r -> r.getStatus() == ReviewRequest.RequestStatus.SENT ||
                r.getStatus() == ReviewRequest.RequestStatus.DELIVERED ||
                r.getStatus() == ReviewRequest.RequestStatus.OPENED ||
                r.getStatus() == ReviewRequest.RequestStatus.CLICKED ||
                r.getStatus() == ReviewRequest.RequestStatus.COMPLETED).count();
        long opened = allRequests.stream().filter(r -> r.getOpenedAt() != null).count();
        long clicked = allRequests.stream().filter(r -> r.getClickedAt() != null).count();
        long completed = allRequests.stream().filter(r -> r.getStatus() == ReviewRequest.RequestStatus.COMPLETED).count();

        Map<String, Object> stats = Map.of(
                "totalRequests", allRequests.size(),
                "totalSent", totalSent,
                "successfulSends", successful,
                "opened", opened,
                "clicked", clicked,
                "completed", completed,
                "openRate", totalSent > 0 ? Math.round((double) opened / totalSent * 100.0) : 0,
                "clickRate", totalSent > 0 ? Math.round((double) clicked / totalSent * 100.0) : 0,
                "completionRate", totalSent > 0 ? Math.round((double) completed / totalSent * 100.0) : 0
        );

        return ResponseEntity.ok(stats);
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}