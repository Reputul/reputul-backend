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

            // Use repository method that validates ownership and eagerly fetches business
            Customer customer = customerRepository.findByIdAndBusinessUser(customerId, user)
                    .orElseThrow(() -> new RuntimeException("Customer not found or access denied"));

            // Validate delivery method requirements
            if ("SMS".equals(deliveryMethod)) {
                if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
                    return ResponseEntity.ok(Map.of(
                            "status", "FAILED",
                            "errorMessage", "Customer phone number is required for SMS delivery"
                    ));
                }

                if (!smsService.isValidPhoneNumber(customer.getPhone())) {
                    return ResponseEntity.ok(Map.of(
                            "status", "FAILED",
                            "errorMessage", "Invalid phone number format"
                    ));
                }
            }

            ReviewRequestDto result;

            // Send based on delivery method
            if ("SMS".equals(deliveryMethod)) {
                result = reviewRequestService.sendSmsReviewRequest(user, customerId);
            } else {
                // Default to email
                result = reviewRequestService.sendReviewRequestWithDefaultTemplate(user, customerId);
            }

            // Return success response
            return ResponseEntity.ok(Map.of(
                    "status", "SENT",
                    "deliveryMethod", deliveryMethod,
                    "message", deliveryMethod + " review request sent successfully!",
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

            // Extract customer info
            String customerEmail = request.get("customerEmail") != null ? request.get("customerEmail").toString() : null;
            String customerPhone = request.get("customerPhone") != null ? request.get("customerPhone").toString() : null;
            String customerName = request.get("customerName") != null ? request.get("customerName").toString() : "Valued Customer";
            String message = request.getOrDefault("message", "").toString();
            String deliveryMethod = request.getOrDefault("deliveryMethod", "EMAIL").toString().toUpperCase();

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

            // If we have a customerId, send to that customer
            if (customerId != null) {
                // Use repository method that eagerly fetches business and user to avoid LazyInitializationException
                Customer customer = customerRepository.findByIdAndBusinessUser(customerId, user)
                        .orElseThrow(() -> new RuntimeException("Customer not found or access denied"));

                // Send based on delivery method
                if ("SMS".equals(deliveryMethod)) {
                    if (customer.getPhone() == null || customer.getPhone().trim().isEmpty()) {
                        return ResponseEntity.ok(Map.of(
                                "status", "FAILED",
                                "errorMessage", "Customer phone number is required for SMS delivery"
                        ));
                    }
                    reviewRequestService.sendSmsReviewRequest(user, customerId);
                } else {
                    reviewRequestService.sendReviewRequestWithDefaultTemplate(user, customerId);
                }

                return ResponseEntity.ok(Map.of(
                        "status", "SENT",
                        "deliveryMethod", deliveryMethod,
                        "message", deliveryMethod + " review request sent successfully!",
                        "recipient", "SMS".equals(deliveryMethod) ? customer.getPhone() : customer.getEmail()
                ));
            }

            // If no customerId, we need businessId for direct send
            if (businessId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Business ID is required for direct review requests"
                ));
            }

            // Validate delivery requirements
            if ("EMAIL".equals(deliveryMethod) && (customerEmail == null || customerEmail.trim().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Customer email is required for email delivery"
                ));
            }

            if ("SMS".equals(deliveryMethod) && (customerPhone == null || customerPhone.trim().isEmpty())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "FAILED",
                        "errorMessage", "Customer phone number is required for SMS delivery"
                ));
            }

            // Get the actual business and validate it belongs to the user
            Business business = businessRepository.findById(businessId)
                    .orElseThrow(() -> new RuntimeException("Business not found"));

            if (!business.getUser().getId().equals(user.getId())) {
                throw new RuntimeException("Access denied: Business does not belong to you");
            }

            // Use actual business data
            String businessName = business.getName() != null ? business.getName() : "Your Business";
            String businessPhone = business.getPhone() != null ? business.getPhone() : "";
            String businessWebsite = business.getWebsite() != null ? business.getWebsite() : "";

            // Generate review URLs using actual business data
            String baseUrl = "https://reputul.com";

            // Generate Google review URL from googlePlaceId if available
            String googleReviewUrl = business.getGooglePlaceId() != null ?
                    "https://search.google.com/local/writereview?placeid=" + business.getGooglePlaceId() :
                    "https://www.google.com/search?q=" + businessName.replace(" ", "+") + "+reviews";

            // Generate Facebook review URL from facebookPageUrl if available
            String facebookReviewUrl = business.getFacebookPageUrl() != null ?
                    business.getFacebookPageUrl() + "/reviews" :
                    "https://www.facebook.com/search/top?q=" + businessName.replace(" ", "%20");

            // Generate private feedback URL
            String privateReviewUrl = baseUrl + "/feedback/" + businessId;
            String unsubscribeUrl = baseUrl + "/unsubscribe/" + businessId;

            // Handle SMS delivery
            if ("SMS".equals(deliveryMethod)) {
                String feedbackGateUrl = baseUrl + "/feedback/" + businessId;

                String smsMessage = String.format(
                        "Hi %s! Thanks for choosing %s. We'd love your feedback: %s Reply STOP to opt out.",
                        customerName,
                        businessName,
                        feedbackGateUrl
                );

                // Send SMS using sendTestSms (2 params)
                SmsService.SmsResult result = smsService.sendTestSms(customerPhone, smsMessage);

                if (result.isSuccess()) {
                    log.info("✅ SMS review request sent successfully to {}", customerPhone);
                    return ResponseEntity.ok(Map.of(
                            "status", "SENT",
                            "deliveryMethod", "SMS",
                            "message", "SMS review request sent successfully!",
                            "recipient", customerPhone
                    ));
                } else {
                    log.error("❌ Failed to send SMS to {}: {}", customerPhone, result.getErrorMessage());
                    return ResponseEntity.ok(Map.of(
                            "status", "FAILED",
                            "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : "Failed to send SMS"
                    ));
                }
            }

            // Handle Email delivery
            // Get the default email template (ID 1)
            EmailTemplate template = emailTemplateRepository.findById(1L)
                    .orElseThrow(() -> new RuntimeException("Default email template not found"));

            // Replace template variables with actual business data
            String emailBody = template.getBody()
                    .replace("{{customerName}}", customerName)
                    .replace("{{businessName}}", businessName)
                    .replace("{{businessPhone}}", businessPhone)
                    .replace("{{businessWebsite}}", businessWebsite)
                    .replace("{{serviceType}}", business.getIndustry() != null ? business.getIndustry() : "Service")
                    .replace("{{serviceDate}}", java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")))
                    .replace("{{googleReviewUrl}}", googleReviewUrl)
                    .replace("{{facebookReviewUrl}}", facebookReviewUrl)
                    .replace("{{privateReviewUrl}}", privateReviewUrl)
                    .replace("{{unsubscribeUrl}}", unsubscribeUrl);

            // Add custom message if provided
            if (!message.isEmpty()) {
                emailBody = emailBody.replace("We hope you were completely satisfied with our service.",
                        "We hope you were completely satisfied with our service. " + message);
            }

            String subject = "Share Your Experience with " + businessName;

            // Send the email using sendTestEmail (3 params: to, subject, body)
            boolean sent = emailService.sendTestEmail(customerEmail, subject, emailBody);

            if (sent) {
                log.info("✅ Review request email sent successfully to {}", customerEmail);
            } else {
                log.error("❌ Failed to send review request email to {}", customerEmail);
            }

            return ResponseEntity.ok(Map.of(
                    "status", sent ? "SENT" : "FAILED",
                    "deliveryMethod", "EMAIL",
                    "message", sent ? "Review request sent successfully!" : "Failed to send email",
                    "recipient", customerEmail,
                    "businessName", businessName,
                    "googleReviewUrl", googleReviewUrl,
                    "facebookReviewUrl", facebookReviewUrl
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

    @GetMapping("")
    public ResponseEntity<List<ReviewRequestDto>> getAllReviewRequests(Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            List<ReviewRequestDto> requests = reviewRequestService.getAllReviewRequestsByUser(user);
            return ResponseEntity.ok(requests);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
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