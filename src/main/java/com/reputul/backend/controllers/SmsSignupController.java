package com.reputul.backend.controllers;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.SmsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Public SMS Signup Controller for Twilio Compliance
 * Handles public SMS consent registration
 */
@RestController
@RequestMapping("/api/v1/sms-signup")
@RequiredArgsConstructor
@Slf4j
public class SmsSignupController {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final SmsService smsService;

    /**
     * Public SMS signup endpoint (no authentication required)
     * Creates customer record and sends double opt-in confirmation
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> signupForSms(@Valid @RequestBody SmsSignupRequest request) {
        try {
            log.info("Public SMS signup request for phone: {}, business: {}",
                    maskPhoneNumber(request.getPhone()), request.getBusinessName());

            // Validate phone format
            String cleanPhone = cleanPhoneNumber(request.getPhone());
            if (!isValidPhoneNumber(cleanPhone)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Invalid phone number format"
                ));
            }

            // Check if customer already exists
            Optional<Customer> existingCustomer = customerRepository.findByPhoneAnyFormat(cleanPhone);
            if (existingCustomer.isPresent()) {
                Customer customer = existingCustomer.get();

                // If already opted in, just confirm
                if (customer.getSmsOptIn() != null && customer.getSmsOptIn() &&
                        (customer.getSmsOptOut() == null || !customer.getSmsOptOut())) {
                    return ResponseEntity.ok(Map.of(
                            "success", true,
                            "message", "You're already subscribed to SMS notifications!",
                            "status", "already_subscribed"
                    ));
                }

                // If previously opted out, allow re-subscription
                customer.recordSmsOptIn(Customer.SmsOptInMethod.WEB_FORM, "sms_signup_page");
                customerRepository.save(customer);

                // Send double opt-in confirmation
                sendDoubleOptInConfirmation(customer);

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Confirmation text sent! Reply YES to complete your subscription.",
                        "status", "resubscribed",
                        "customerId", customer.getId()
                ));
            }

            // Find or create business and user
            Business business = findOrCreateBusinessForSignup(request.getBusinessName());

            // Create new customer record
            Customer customer = Customer.builder()
                    .name(request.getName())
                    .phone(cleanPhone)
                    .email(generatePlaceholderEmail(cleanPhone))
                    .serviceDate(LocalDate.now())
                    .serviceType(request.getServiceType())
                    .status(Customer.CustomerStatus.COMPLETED)
                    .business(business)
                    .user(business.getUser())
                    .smsOptIn(false)
                    .smsOptOut(false)
                    .build();

            customer = customerRepository.save(customer);

            // Send double opt-in confirmation SMS
            sendDoubleOptInConfirmation(customer);

            log.info("✅ SMS signup successful for customer {} - double opt-in sent", customer.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Confirmation text sent! Reply YES to complete your subscription.",
                    "status", "confirmation_sent",
                    "customerId", customer.getId()
            ));

        } catch (Exception e) {
            log.error("❌ SMS signup failed for phone {}: {}",
                    maskPhoneNumber(request.getPhone()), e.getMessage(), e);

            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "Signup failed. Please try again later.",
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Send double opt-in confirmation SMS
     */
    private void sendDoubleOptInConfirmation(Customer customer) {
        try {
            String message = String.format(
                    "%s: Thanks for signing up! To complete your subscription and receive review notifications, " +
                            "reply YES. Reply STOP to opt out. Msg & data rates may apply. Support: %s",
                    customer.getBusiness().getName(),
                    getSupportContact()
            );

            SmsService.SmsResult result = smsService.sendTestSms(customer.getPhone(), message);

            if (result.isSuccess()) {
                log.info("✅ Double opt-in confirmation sent to customer {}", customer.getId());
            } else {
                log.error("❌ Failed to send double opt-in confirmation: {}", result.getErrorMessage());
            }

        } catch (Exception e) {
            log.error("❌ Error sending double opt-in confirmation to customer {}: {}",
                    customer.getId(), e.getMessage());
        }
    }

    /**
     * Find existing business or create a placeholder for SMS signup
     */
    private Business findOrCreateBusinessForSignup(String businessName) {
        // Try to find existing business by name (case-insensitive)
        Optional<Business> existingBusiness = businessRepository
                .findAll()
                .stream()
                .filter(b -> b.getName().equalsIgnoreCase(businessName))
                .findFirst();

        if (existingBusiness.isPresent()) {
            return existingBusiness.get();
        }

        // Create placeholder user and business for SMS signups
        // In production, you might want to have a dedicated "SMS Signups" user
        User signupUser = userRepository.findByEmail("sms-signups@reputul.com")
                .orElseGet(() -> {
                    User newUser = User.builder()
                            .email("sms-signups@reputul.com")
                            .name("SMS Signups")
                            .password("placeholder-password") // Required field
                            .build();
                    return userRepository.save(newUser);
                });

        Business business = Business.builder()
                .name(businessName)
                .industry("Local Service")
                .user(signupUser)
                .build();

        return businessRepository.save(business);
    }

    /**
     * Clean and validate phone number
     */
    private String cleanPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return null;

        String cleaned = phoneNumber.replaceAll("[^+\\d]", "");

        if (!cleaned.startsWith("+")) {
            if (cleaned.length() == 10) {
                cleaned = "+1" + cleaned;
            } else if (cleaned.length() == 11 && cleaned.startsWith("1")) {
                cleaned = "+" + cleaned;
            } else {
                cleaned = "+" + cleaned;
            }
        }

        return cleaned;
    }

    /**
     * Generate a unique placeholder email for SMS-only signups
     */
    private String generatePlaceholderEmail(String phone) {
        // Create a unique email using the phone number
        String phoneDigits = phone.replaceAll("[^0-9]", "");
        return "sms+" + phoneDigits + "@reputul.com";
    }

    /**
     * Validate US phone number format
     */
    private boolean isValidPhoneNumber(String phoneNumber) {
        if (phoneNumber == null) return false;

        // US phone number pattern: +1XXXXXXXXXX
        return phoneNumber.matches("\\+1\\d{10}");
    }

    /**
     * Mask phone number for logging
     */
    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        return phoneNumber.substring(0, Math.min(3, phoneNumber.length())) +
                "*".repeat(Math.max(0, phoneNumber.length() - 7)) +
                phoneNumber.substring(Math.max(3, phoneNumber.length() - 4));
    }

    private String getSupportContact() {
        // You can inject this from properties if needed
        return "support@reputul.com";
    }

    /**
     * Health check for SMS signup endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "healthy",
                "service", "sms-signup",
                "endpoints", Map.of(
                        "signup", "POST /api/sms-signup",
                        "health", "GET /api/sms-signup/health"
                )
        ));
    }

    /**
     * Request DTO for SMS signup
     */
    public static class SmsSignupRequest {

        @NotBlank(message = "Name is required")
        private String name;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?1?[-\\.\\s]?\\(?([0-9]{3})\\)?[-\\.\\s]?([0-9]{3})[-\\.\\s]?([0-9]{4})$",
                message = "Invalid US phone number format")
        private String phone;

        @NotBlank(message = "Business name is required")
        private String businessName;

        private String serviceType = "service";

        private boolean consent = false;

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }

        public String getBusinessName() { return businessName; }
        public void setBusinessName(String businessName) { this.businessName = businessName; }

        public String getServiceType() { return serviceType; }
        public void setServiceType(String serviceType) { this.serviceType = serviceType; }

        public boolean isConsent() { return consent; }
        public void setConsent(boolean consent) { this.consent = consent; }
    }
}