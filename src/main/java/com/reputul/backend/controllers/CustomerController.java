package com.reputul.backend.controllers;

import com.reputul.backend.dto.CreateCustomerRequest;
import com.reputul.backend.dto.CustomerDto;
import com.reputul.backend.dto.CustomerStatsDto;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.security.CurrentUser;
import com.reputul.backend.services.AutomationTriggerService;
import com.reputul.backend.services.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Slf4j  // Added for logging
public class CustomerController {

    private final CustomerService customerService;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final AutomationTriggerService automationTriggerService;

    @GetMapping("/test")
    public ResponseEntity<String> testEndpoint(Authentication authentication) {
        return ResponseEntity.ok("Customer API working! User: " + authentication.getName());
    }

    @GetMapping
    public ResponseEntity<List<CustomerDto>> getAllCustomers(Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<CustomerDto> customers = customerService.getAllCustomersByUser(user);
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/business/{businessId}")
    public ResponseEntity<List<CustomerDto>> getCustomersByBusiness(
            @PathVariable Long businessId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<CustomerDto> customers = customerService.getCustomersByBusiness(user, businessId);
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerDto> getCustomerById(
            @PathVariable Long customerId,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        CustomerDto customer = customerService.getCustomerById(user, customerId);
        return ResponseEntity.ok(customer);
    }

    @PostMapping
    public ResponseEntity<?> createCustomer(
            @RequestBody CreateCustomerRequest request,
            Authentication authentication) {
        try {
            log.info("📝 Creating customer: name={}, email={}, businessId={}",
                    request.getName(), request.getEmail(), request.getBusinessId());

            User user = getCurrentUser(authentication);
            CustomerDto customerDto = customerService.createCustomer(user, request);

            // Trigger automation on customer creation
            try {
                // Get the actual Customer entity for automation
                Customer customer = customerRepository.findById(customerDto.getId())
                        .orElseThrow(() -> new RuntimeException("Customer not found after creation"));

                automationTriggerService.onCustomerCreated(customer);
                log.info("✅ Triggered automation workflows for customer: {}", customer.getName());
            } catch (Exception e) {
                log.error("❌ Failed to trigger automation for customer: {}", e.getMessage());
                // Don't fail customer creation if automation fails
            }

            return ResponseEntity.ok(customerDto);
        } catch (RuntimeException e) {
            log.error("❌ Error creating customer: {}", e.getMessage());
            e.printStackTrace();
            // FIXED: Return the actual error message instead of empty 400
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "message", "Failed to create customer: " + e.getMessage()
            ));
        }
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<?> updateCustomer(
            @PathVariable Long customerId,
            @RequestBody CreateCustomerRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            CustomerDto customer = customerService.updateCustomer(user, customerId, request);
            return ResponseEntity.ok(customer);
        } catch (RuntimeException e) {
            log.error("❌ Error updating customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "message", "Failed to update customer: " + e.getMessage()
            ));
        }
    }

    @DeleteMapping("/{customerId}")
    public ResponseEntity<?> deleteCustomer(
            @PathVariable Long customerId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            customerService.deleteCustomer(user, customerId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            log.error("❌ Error deleting customer {}: {}", customerId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "message", "Failed to delete customer: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/{customerId}/complete-service")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Map<String, String>> completeService(
            @CurrentUser User user,
            @PathVariable Long customerId,
            @RequestBody(required = false) Map<String, String> request) {

        Customer customer = customerRepository.findByIdAndUser(customerId, user)
                .orElseThrow(() -> new RuntimeException("Customer not found or access denied"));

        // Mark service as completed
        customer.markServiceCompleted();
        customerRepository.save(customer);

        // Trigger automation
        String serviceType = request != null ? request.get("serviceType") : customer.getServiceType();
        automationTriggerService.onServiceCompleted(customer, serviceType);

        Map<String, String> response = Map.of(
                "message", "Service marked complete and automation triggered",
                "customerId", customerId.toString(),
                "status", "COMPLETED"
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<List<CustomerDto>> searchCustomers(
            @RequestParam String q,
            Authentication authentication) {
        User user = getCurrentUser(authentication);
        List<CustomerDto> customers = customerService.searchCustomers(user, q);
        return ResponseEntity.ok(customers);
    }

    @GetMapping("/stats")
    public ResponseEntity<CustomerStatsDto> getCustomerStats(Authentication authentication) {
        User user = getCurrentUser(authentication);
        CustomerStatsDto stats = customerService.getCustomerStats(user);
        return ResponseEntity.ok(stats);
    }

    private User getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}