package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.EmailTemplateRepository;
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
    private final EmailTemplateRepository emailTemplateRepository;

    @PostMapping("")
    public ResponseEntity<?> sendReviewRequest(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Extract data from request
            Long customerId = Long.valueOf(request.get("customerId").toString());

            System.out.println("🚀 Sending review request to customer ID: " + customerId);

            // Use the existing service method (ignores templateId for now)
            ReviewRequestDto result = reviewRequestService.sendReviewRequestWithDefaultTemplate(user, customerId);

            // Return success response
            return ResponseEntity.ok(Map.of(
                    "status", "SENT",
                    "message", "Review request sent successfully!",
                    "customerEmail", result.getCustomerEmail() != null ? result.getCustomerEmail() : "N/A"
            ));

        } catch (Exception e) {
            System.err.println("❌ Error sending review request: " + e.getMessage());
            e.printStackTrace();

            return ResponseEntity.ok(Map.of(
                    "status", "FAILED",
                    "errorMessage", e.getMessage()
            ));
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
                        return reviewRequestService.sendReviewRequestWithDefaultTemplate(user, singleRequest.getCustomerId());
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

    // Add this method to your existing ReviewRequestController class

    @PostMapping("/fix-template")
    public ResponseEntity<Map<String, Object>> fixTemplate(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Get template ID 1 (the one being used)
            EmailTemplate template = emailTemplateRepository.findById(1L).orElse(null);
            if (template == null) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "Template ID 1 not found"
                ));
            }

            // Update it to use HTML content with buttons
            template.setBody("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Share Your Experience</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; line-height: 1.6; color: #333; background-color: #f4f4f4;">
                <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                    
                    <!-- Header -->
                    <div style="background-color: #2563eb; color: white; padding: 30px 20px; text-align: center;">
                        <h1 style="margin: 0; font-size: 24px;">{{businessName}}</h1>
                        <p style="margin: 5px 0 0 0; opacity: 0.9;">We value your feedback</p>
                    </div>
                    
                    <!-- Main Content -->
                    <div style="padding: 30px 20px;">
                        <p style="margin: 0 0 15px 0; font-size: 16px;">Hi {{customerName}},</p>
                        <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you for choosing {{businessName}} for your {{serviceType}} on {{serviceDate}}.</p>
                        <p style="margin: 0 0 25px 0; font-size: 16px;">We hope you were completely satisfied with our service. Your honest feedback helps us improve and assists other customers in making informed decisions.</p>
                        
                        <!-- Review Buttons Section -->
                        <div style="background-color: #f8fafc; padding: 25px; border-radius: 8px; text-align: center; margin: 25px 0;">
                            <h2 style="margin: 0 0 20px 0; color: #374151; font-size: 20px;">Share Your Experience</h2>
                            <p style="margin: 0 0 20px 0; color: #6b7280; font-size: 14px;">Choose your preferred platform:</p>
                            
                            <!-- Google Review Button -->
                            <div style="margin-bottom: 15px;">
                                <a href="{{googleReviewUrl}}" style="display: inline-block; background-color: #16a34a; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                    🌟 Leave Google Review
                                </a>
                            </div>
                            
                            <!-- Facebook Review Button -->
                            <div style="margin-bottom: 15px;">
                                <a href="{{facebookReviewUrl}}" style="display: inline-block; background-color: #1877f2; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                    📘 Facebook Review
                                </a>
                            </div>
                            
                            <!-- Private Feedback Button -->
                            <div style="margin-bottom: 15px;">
                                <a href="{{privateReviewUrl}}" style="display: inline-block; background-color: #6b7280; color: white; text-decoration: none; padding: 15px 25px; border-radius: 6px; font-weight: bold; font-size: 16px; min-width: 200px; text-align: center;">
                                    💬 Private Feedback
                                </a>
                            </div>
                        </div>
                    </div>
                    
                    <!-- Footer -->
                    <div style="background-color: #f9fafb; padding: 20px; border-top: 1px solid #e5e7eb; text-align: center;">
                        <p style="margin: 0 0 5px 0; font-weight: bold; color: #374151;">{{businessName}}</p>
                        <p style="margin: 0 0 5px 0; font-size: 14px; color: #6b7280;">{{businessPhone}}</p>
                        <p style="margin: 0 0 15px 0; font-size: 14px; color: #6b7280;">{{businessWebsite}}</p>
                        <p style="margin: 0; font-size: 12px; color: #9ca3af;">
                            <a href="{{unsubscribeUrl}}" style="color: #6b7280; text-decoration: none;">Unsubscribe</a>
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """);

            template.setName("HTML Review Request Email (Fixed)");
            emailTemplateRepository.save(template);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "SUCCESS: Updated template ID 1 to use HTML with buttons!",
                    "templateId", template.getId(),
                    "templateName", template.getName()
            ));

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Error: " + e.getMessage()
            ));
        }
    }
}