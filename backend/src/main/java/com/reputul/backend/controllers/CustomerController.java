package com.reputul.backend.controllers;

import com.reputul.backend.dto.CreateCustomerRequest;
import com.reputul.backend.dto.CustomerDto;
import com.reputul.backend.dto.CustomerStatsDto;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
// REMOVED: @CrossOrigin(origins = "*") - this was causing the CORS conflict
public class CustomerController {

    private final CustomerService customerService;
    private final UserRepository userRepository;

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
    public ResponseEntity<CustomerDto> createCustomer(
            @RequestBody CreateCustomerRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            CustomerDto customer = customerService.createCustomer(user, request);
            return ResponseEntity.ok(customer);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<CustomerDto> updateCustomer(
            @PathVariable Long customerId,
            @RequestBody CreateCustomerRequest request,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            CustomerDto customer = customerService.updateCustomer(user, customerId, request);
            return ResponseEntity.ok(customer);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{customerId}")
    public ResponseEntity<Void> deleteCustomer(
            @PathVariable Long customerId,
            Authentication authentication) {
        try {
            User user = getCurrentUser(authentication);
            customerService.deleteCustomer(user, customerId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
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