package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.*;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.EmailTemplateRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.services.ReviewRequestService;
import com.reputul.backend.services.EmailService;
import com.reputul.backend.services.SmsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/review-requests")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:3000")
@Slf4j
public class ReviewRequestController {

    private final ReviewRequestService reviewRequestService;
    private final EmailService emailService;
    private final SmsService smsService;
    private final UserRepository userRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;

    @PostMapping("")
    public ResponseEntity<?> sendReviewRequest(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // Extract data from request
            Long customerId = Long.valueOf(request.get("customerId").toString());
            String deliveryMethod = request.getOrDefault("deliveryMethod", "EMAIL").toString().toUpperCase();

            log.info("🚀 Sending {} review request to customer ID: {}", deliveryMethod, customerId);

            // FIX: Use the new eager loading method to fetch customer with business and user
            Customer customer = customerRepository.findByIdWithBusinessAndUser(customerId)
                    .orElseThrow(() -> new RuntimeException("Customer not found"));

            // Now business and business.user are already loaded - no lazy loading!
            Business business = customer.getBusiness();
            if (business == null) {
                throw new RuntimeException("Customer has no associated business");
            }

            User businessOwner = business.getUser();
            if (businessOwner == null) {
                throw new RuntimeException("Business has no owner");
            }

            // Check if it belongs to the current user
            if (!businessOwner.getId().equals(user.getId())) {
                throw new RuntimeException("Access denied: Customer does not belong to your business");
            }

            // Validate delivery method requirements
            if ("SMS".equals(deliveryMethod)) {
                if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
                    return ResponseEntity.ok(Map.of(
                            "status", "FAILED",
                            "errorMessage", "Customer phone number is required for SMS delivery"
                    ));
                }
            } else if ("EMAIL".equals(deliveryMethod)) {
                if (customer.getEmail() == null || customer.getEmail().trim().isEmpty()) {
                    return ResponseEntity.ok(Map.of(
                            "status", "FAILED",
                            "errorMessage", "Customer email is required for EMAIL delivery"
                    ));
                }
            }

            // Send review request using the service
            ReviewRequestDto result = reviewRequestService.sendReviewRequestWithDefaultTemplate(user, customerId);

            return ResponseEntity.ok(Map.of(
                    "status", result != null ? "SENT" : "FAILED",
                    "deliveryMethod", deliveryMethod,
                    "message", result != null ? "Review request sent successfully!" : "Failed to send request",
                    "recipient", "SMS".equals(deliveryMethod) ?
                            customer.getPhone() : customer.getEmail()
            ));

        } catch (Exception e) {
            log.error("❌ Error sending review request: {}", e.getMessage());
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

    @PostMapping("/send-direct")
    public ResponseEntity<?> sendDirectReviewRequest(
            @RequestBody Map<String, Object> request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);

            // FIXED: Accept both "businessId" and "selectedBusiness" for backward compatibility
            Long businessId = null;
            if (request.get("businessId") != null) {
                businessId = Long.valueOf(request.get("businessId").toString());
            } else if (request.get("selectedBusiness") != null) {
                businessId = Long.valueOf(request.get("selectedBusiness").toString());
            }

            // FIX: Proper null handling for all optional fields
            String customerEmail = request.get("customerEmail") != null ? request.get("customerEmail").toString() : null;
            String customerPhone = request.get("customerPhone") != null ? request.get("customerPhone").toString() : null;
            String customerName = request.get("customerName") != null ? request.get("customerName").toString() : "Valued Customer";

            // FIX: Handle null message values properly
            Object messageObj = request.get("message");
            String message = (messageObj != null && !messageObj.toString().isEmpty()) ? messageObj.toString() : "";

            // FIX: Handle null deliveryMethod values properly
            Object deliveryMethodObj = request.get("deliveryMethod");
            String deliveryMethod = (deliveryMethodObj != null) ? deliveryMethodObj.toString().toUpperCase() : "EMAIL";

            // If customerId is provided, use that customer directly
            Long customerId = null;
            if (request.get("customerId") != null) {
                customerId = Long.valueOf(request.get("customerId").toString());
            }

            log.info("🚀 Sending direct {} review request to: {} (customerId: {}, businessId: {})",
                    deliveryMethod,
                    "EMAIL".equals(deliveryMethod) ? customerEmail : customerPhone,
                    customerId,
                    businessId);

            // Validation
            if (businessId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Business ID is required"
                ));
            }

            if ("EMAIL".equals(deliveryMethod) && (customerEmail == null || customerEmail.trim().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Customer email is required for email delivery"
                ));
            }

            if ("SMS".equals(deliveryMethod) && (customerPhone == null || customerPhone.trim().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Customer phone is required for SMS delivery"
                ));
            }

            // Get and validate business
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new RuntimeException("Business not found"));

            if (!business.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied: Business does not belong to you");
            }

            String businessName = business.getName() != null ? business.getName() : "Our Business";

            // FIX: Extract googleReviewUrl (optional field) with proper null handling
            Object googleReviewUrlObj = request.get("googleReviewUrl");
            String googleReviewUrl = (googleReviewUrlObj != null && !googleReviewUrlObj.toString().trim().isEmpty())
                    ? googleReviewUrlObj.toString()
                    : null;

            // Use business's auto-detected Google URL if not provided
            if (googleReviewUrl == null || googleReviewUrl.trim().isEmpty()) {
                if (business.getGoogleReviewUrl() != null && !business.getGoogleReviewUrl().trim().isEmpty()) {
                    googleReviewUrl = business.getGoogleReviewUrl();
                } else if (business.getGoogleReviewShortUrl() != null && !business.getGoogleReviewShortUrl().trim().isEmpty()) {
                    googleReviewUrl = business.getGoogleReviewShortUrl();
                } else if (business.getGoogleSearchUrl() != null && !business.getGoogleSearchUrl().trim().isEmpty()) {
                    googleReviewUrl = business.getGoogleSearchUrl();
                } else {
                    // Fallback to generic search
                    googleReviewUrl = "https://www.google.com/search?q=" +
                            businessName.replace(" ", "+") + "+reviews";
                }
            }

            String facebookReviewUrl = business.getFacebookPageUrl() != null ?
                    business.getFacebookPageUrl() : null;

            log.info("📧 Preparing {} message for {}", deliveryMethod,
                    "EMAIL".equals(deliveryMethod) ? customerEmail : customerPhone);
            log.info("   Business: {}", businessName);
            log.info("   Google URL: {}", googleReviewUrl);

            boolean sent = false;

            if ("EMAIL".equals(deliveryMethod)) {
                // Build and send email
                String emailHtml = buildReviewRequestEmailHtml(
                        customerName,
                        businessName,
                        googleReviewUrl,
                        facebookReviewUrl,
                        message
                );

                String subject = "We'd love your feedback!";
                sent = emailService.sendTestEmail(customerEmail, subject, emailHtml);
            } else if ("SMS".equals(deliveryMethod)) {
                // Build and send SMS
                String smsMessage = String.format(
                        "Hi %s! Thanks for choosing %s. We'd love your feedback! Leave a review: %s",
                        customerName,
                        businessName,
                        googleReviewUrl
                );

                SmsService.SmsResult smsResult = smsService.sendTestSms(customerPhone, smsMessage);
                sent = smsResult.isSuccess();

                if (!sent) {
                    log.error("SMS failed: {}", smsResult.getErrorMessage());
                }
            }

            log.info(sent ? "✅ {} sent successfully" : "❌ {} failed to send", deliveryMethod);

            return ResponseEntity.ok(Map.of(
                    "status", sent ? "SENT" : "FAILED",
                    "deliveryMethod", deliveryMethod,
                    "message", sent ? "Review request sent successfully!" : "Failed to send " + deliveryMethod.toLowerCase(),
                    "recipient", "EMAIL".equals(deliveryMethod) ? customerEmail : customerPhone,
                    "businessName", businessName,
                    "googleReviewUrl", googleReviewUrl != null ? googleReviewUrl : "",
                    "facebookReviewUrl", facebookReviewUrl != null ? facebookReviewUrl : ""
            ));

        } catch (Exception e) {
            log.error("❌ Error sending direct review request: {}", e.getMessage());
            e.printStackTrace();

            return ResponseEntity.ok(Map.of(
                    "status", "FAILED",
                    "errorMessage", e.getMessage()
            ));
        }
    }

    /**
     * Helper method to build review request email HTML
     */
    private String buildReviewRequestEmailHtml(String customerName, String businessName,
                                               String googleReviewUrl, String facebookReviewUrl,
                                               String customMessage) {
        String googleButton = googleReviewUrl != null && !googleReviewUrl.trim().isEmpty() ?
                String.format("""
                    <a href="%s" style="display: inline-block; margin: 10px; padding: 15px 30px; background-color: #4285f4; color: white; text-decoration: none; border-radius: 5px; font-weight: bold;">
                        Leave a Google Review
                    </a>
                    """, googleReviewUrl) : "";

        String facebookButton = facebookReviewUrl != null && !facebookReviewUrl.trim().isEmpty() ?
                String.format("""
                    <a href="%s" style="display: inline-block; margin: 10px; padding: 15px 30px; background-color: #1877f2; color: white; text-decoration: none; border-radius: 5px; font-weight: bold;">
                        Leave a Facebook Review
                    </a>
                    """, facebookReviewUrl) : "";

        String messageSection = customMessage != null && !customMessage.trim().isEmpty() ?
                String.format("<p style=\"margin: 20px 0; font-size: 16px; font-style: italic;\">%s</p>", customMessage) : "";

        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Share Your Experience</title>
            </head>
            <body style="margin: 0; padding: 0; font-family: Arial, sans-serif; background-color: #f4f4f4;">
                <div style="max-width: 600px; margin: 20px auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                    
                    <!-- Header -->
                    <div style="background-color: #2563eb; color: white; padding: 30px 20px; text-align: center;">
                        <h1 style="margin: 0; font-size: 24px;">%s</h1>
                        <p style="margin: 5px 0 0 0; opacity: 0.9;">We value your feedback</p>
                    </div>
                    
                    <!-- Main Content -->
                    <div style="padding: 30px 20px;">
                        <p style="margin: 0 0 15px 0; font-size: 16px;">Hi %s,</p>
                        <p style="margin: 0 0 15px 0; font-size: 16px;">Thank you for choosing %s!</p>
                        <p style="margin: 0 0 25px 0; font-size: 16px;">We hope you were completely satisfied with our service. Would you mind taking a moment to share your experience?</p>
                        
                        %s
                        
                        <!-- Review Buttons -->
                        <div style="text-align: center; margin: 30px 0;">
                            %s
                            %s
                        </div>
                        
                        <p style="margin: 25px 0 0 0; font-size: 14px; color: #666; text-align: center;">
                            Your feedback helps us improve and helps others make informed decisions.
                        </p>
                    </div>
                    
                    <!-- Footer -->
                    <div style="background-color: #f8f9fa; padding: 20px; text-align: center; border-top: 1px solid #e9ecef;">
                        <p style="margin: 0; font-size: 12px; color: #6c757d;">
                            © 2024 %s. All rights reserved.
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """, businessName, customerName, businessName, messageSection, googleButton, facebookButton, businessName);
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

    // NEW: Test SMS functionality
    @PostMapping("/test-sms")
    public ResponseEntity<Map<String, Object>> sendTestSms(
            @RequestBody Map<String, String> testRequest,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            String phoneNumber = testRequest.get("phone");
            String message = testRequest.getOrDefault("message",
                    "Test SMS from Reputul - Your SMS service is working correctly!");

            if (!smsService.isValidPhoneNumber(phoneNumber)) {
                return ResponseEntity.ok(Map.of(
                        "success", false,
                        "message", "Invalid phone number format"
                ));
            }

            SmsService.SmsResult result = smsService.sendTestSms(phoneNumber, message);

            return ResponseEntity.ok(Map.of(
                    "success", result.isSuccess(),
                    "message", result.isSuccess() ?
                            "Test SMS sent successfully!" : result.getErrorMessage(),
                    "messageSid", result.getMessageSid() != null ? result.getMessageSid() : "",
                    "status", result.getStatus() != null ? result.getStatus() : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Error: " + e.getMessage()
            ));
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

        // NEW: SMS-specific stats
        long smsRequests = allRequests.stream().filter(r ->
                r.getDeliveryMethod() != null && "SMS".equals(r.getDeliveryMethod())).count();
        long emailRequests = allRequests.size() - smsRequests;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRequests", allRequests.size());
        stats.put("totalSent", totalSent);
        stats.put("successfulSends", successful);
        stats.put("opened", opened);
        stats.put("clicked", clicked);
        stats.put("completed", completed);
        stats.put("smsRequests", smsRequests);
        stats.put("emailRequests", emailRequests);
        stats.put("openRate", totalSent > 0 ? Math.round((double) opened / totalSent * 100.0) : 0);
        stats.put("clickRate", totalSent > 0 ? Math.round((double) clicked / totalSent * 100.0) : 0);
        stats.put("completionRate", totalSent > 0 ? Math.round((double) completed / totalSent * 100.0) : 0);

        return ResponseEntity.ok(stats);
    }

    // NEW: Validate phone number endpoint
    @PostMapping("/validate-phone")
    public ResponseEntity<Map<String, Object>> validatePhoneNumber(
            @RequestBody Map<String, String> request) {
        try {
            String phoneNumber = request.get("phone");
            boolean isValid = smsService.isValidPhoneNumber(phoneNumber);

            return ResponseEntity.ok(Map.of(
                    "valid", isValid,
                    "message", isValid ? "Valid phone number" : "Invalid phone number format",
                    "originalNumber", phoneNumber != null ? phoneNumber : ""
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "message", "Error validating phone number: " + e.getMessage()
            ));
        }
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // Keep existing template fix method for backward compatibility
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