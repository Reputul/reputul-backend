package com.reputul.backend.controllers;

import com.reputul.backend.dto.SmsOptInRequestDto;
import com.reputul.backend.dto.SmsOptInResponseDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.services.SmsService;
import com.reputul.backend.services.UsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * PUBLIC endpoint for customer SMS opt-in
 * Required for Twilio toll-free verification to show customer-initiated consent
 */
@RestController
@RequestMapping("/api/public/sms")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"https://reputul.com", "https://www.reputul.com", "http://localhost:3000"})
public class PublicSmsOptInController {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;
    private final SmsService smsService;
    private final UsageService usageService;

    /**
     * PUBLIC: Customer SMS opt-in endpoint
     * This is what Twilio needs to see - customers can initiate SMS consent themselves
     *
     * Usage: Customer visits /sms-signup?business=123 and submits their info
     */
    @PostMapping("/opt-in")
    public ResponseEntity<?> customerOptIn(@Valid @RequestBody SmsOptInRequestDto request) {
        try {
            log.info("📱 PUBLIC SMS opt-in request - Business: {}, Phone: {}",
                    request.getBusinessId(), maskPhoneNumber(request.getPhone()));

            // Validate business exists
            Optional<Business> businessOpt = businessRepository.findById(request.getBusinessId());
            if (businessOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Business not found"));
            }
            Business business = businessOpt.get();

            // Check if customer already exists
            Optional<Customer> existingCustomer = customerRepository
                    .findByPhoneAnyFormat(request.getPhone());

            Customer customer;
            boolean isNewCustomer = false;

            if (existingCustomer.isPresent()) {
                customer = existingCustomer.get();
                log.info("Existing customer {} opting in for business {}",
                        customer.getId(), business.getId());

                // Check if already opted in for this business
                if (customer.getBusiness().getId().equals(business.getId())
                        && customer.getSmsOptIn() != null && customer.getSmsOptIn()
                        && (customer.getSmsOptOut() == null || !customer.getSmsOptOut()
                        || (customer.getSmsOptOutTimestamp() != null && customer.getSmsOptInTimestamp() != null
                        && customer.getSmsOptOutTimestamp().isBefore(customer.getSmsOptInTimestamp())))) {
                    return ResponseEntity.ok(SmsOptInResponseDto.builder()
                            .success(true)
                            .message("You're already subscribed to SMS updates!")
                            .requiresConfirmation(false)
                            .build());
                }
            } else {
                // Create new customer
                customer = new Customer();
                customer.setBusiness(business);
                customer.setPhone(request.getPhone());
                customer.setName(request.getName());
                customer.setEmail(request.getEmail());
                customer.setServiceType(request.getServiceType() != null ? request.getServiceType() : "SMS_SIGNUP");
                customer.setStatus(Customer.CustomerStatus.PENDING);
                customer.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                isNewCustomer = true;
                log.info("Creating new customer for SMS opt-in");
            }

            // Record the opt-in attempt (pending confirmation)
            customer.recordSmsOptIn(Customer.SmsOptInMethod.WEB_FORM, "public_optin_form");
            Customer savedCustomer = customerRepository.save(customer);

            // Send double opt-in confirmation SMS
            String confirmationMessage = createDoubleOptInMessage(business, customer);
            SmsService.SmsResult smsResult = smsService.sendTestSms(customer.getPhone(), confirmationMessage);

            if (!smsResult.isSuccess()) {
                log.error("Failed to send double opt-in SMS to {}: {}",
                        maskPhoneNumber(customer.getPhone()), smsResult.getErrorMessage());
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Failed to send confirmation SMS: " + smsResult.getErrorMessage()));
            }

            // Track usage for billing - using existing UsageService
            try {
                // Using your actual UsageService API: recordSmsUsage(Business business, String requestId)
                usageService.recordSmsUsage(business, "double_optin_" + savedCustomer.getId());
            } catch (Exception e) {
                log.warn("Failed to record SMS usage: {}", e.getMessage());
                // Don't fail the whole request if usage tracking fails
            }

            log.info("✅ Double opt-in SMS sent to customer {} for business {} - SID: {}",
                    savedCustomer.getId(), business.getId(), smsResult.getMessageSid());

            return ResponseEntity.ok(SmsOptInResponseDto.builder()
                    .success(true)
                    .message("Check your phone! We sent a confirmation text. Reply YES to confirm your subscription.")
                    .requiresConfirmation(true)
                    .customerId(savedCustomer.getId())
                    .businessName(business.getName())
                    .nextStep("Reply YES to the confirmation text to complete your subscription")
                    .build());

        } catch (Exception e) {
            log.error("Error processing SMS opt-in: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unable to process opt-in request"));
        }
    }

    /**
     * PUBLIC: Check SMS subscription status
     * Allows customers to verify their current subscription status
     */
    @GetMapping("/status")
    public ResponseEntity<?> checkOptInStatus(@RequestParam String phone,
                                              @RequestParam Long businessId) {
        try {
            Optional<Customer> customerOpt = customerRepository.findByPhoneAnyFormat(phone);

            if (customerOpt.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "subscribed", false,
                        "message", "Phone number not found in our system"
                ));
            }

            Customer customer = customerOpt.get();
            boolean isSubscribed = customer.getSmsOptIn() != null && customer.getSmsOptIn()
                    && (customer.getSmsOptOut() == null || !customer.getSmsOptOut()
                    || (customer.getSmsOptOutTimestamp() != null && customer.getSmsOptInTimestamp() != null
                    && customer.getSmsOptOutTimestamp().isBefore(customer.getSmsOptInTimestamp())));

            return ResponseEntity.ok(Map.of(
                    "subscribed", isSubscribed,
                    "businessName", customer.getBusiness().getName(),
                    "optInDate", customer.getSmsOptInTimestamp(),
                    "optOutDate", customer.getSmsOptOutTimestamp()
            ));

        } catch (Exception e) {
            log.error("Error checking SMS status: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unable to check status"));
        }
    }

    /**
     * PUBLIC: Get business info for SMS signup form
     * Allows the signup form to display business details
     */
    @GetMapping("/business/{businessId}")
    public ResponseEntity<?> getBusinessInfo(@PathVariable Long businessId) {
        try {
            Optional<Business> businessOpt = businessRepository.findById(businessId);

            if (businessOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Business business = businessOpt.get();

            return ResponseEntity.ok(Map.of(
                    "id", business.getId(),
                    "name", business.getName(),
                    "industry", business.getIndustry(),
                    "phone", business.getPhone() != null ? business.getPhone() : "",
                    "website", business.getWebsite() != null ? business.getWebsite() : "",
                    "address", business.getAddress() != null ? business.getAddress() : ""
            ));

        } catch (Exception e) {
            log.error("Error fetching business info: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unable to fetch business information"));
        }
    }

    /**
     * Create double opt-in confirmation message
     * This is required by carriers and shows explicit consent
     */
    private String createDoubleOptInMessage(Business business, Customer customer) {
        return String.format(
                "%s: Hi %s! You requested SMS updates about your service. " +
                        "Reply YES to confirm subscription or STOP to cancel. " +
                        "Msg rates may apply. Help: %s",
                business.getName(),
                getFirstName(customer.getName()),
                business.getPhone() != null ? business.getPhone() : "support@reputul.com"
        );
    }

    /**
     * Get first name from full name for personalization
     */
    private String getFirstName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "there";
        }
        String[] parts = fullName.trim().split("\\s+");
        return parts[0];
    }

    /**
     * Mask phone number for logging (GDPR compliance)
     */
    private String maskPhoneNumber(String phone) {
        if (phone == null || phone.length() < 4) {
            return "***";
        }
        return "***" + phone.substring(phone.length() - 4);
    }
}