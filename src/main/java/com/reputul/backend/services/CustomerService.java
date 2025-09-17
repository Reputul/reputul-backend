package com.reputul.backend.services;

import com.reputul.backend.dto.CreateCustomerRequest;
import com.reputul.backend.dto.CustomerDto;
import com.reputul.backend.dto.CustomerStatsDto;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;

    public List<CustomerDto> getAllCustomersByUser(User user) {
        List<Customer> customers = customerRepository.findByUserOrderByCreatedAtDesc(user);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public List<CustomerDto> getCustomersByBusiness(User user, Long businessId) {
        Business business = businessRepository.findByIdAndUserId(businessId, user.getId())
                .orElseThrow(() -> new RuntimeException("Business not found"));

        List<Customer> customers = customerRepository.findByUserAndBusinessOrderByCreatedAtDesc(user, business);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public CustomerDto getCustomerById(User user, Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        // Verify customer belongs to user
        if (!customer.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Customer not found");
        }

        return convertToDto(customer);
    }

    public CustomerDto createCustomer(User user, CreateCustomerRequest request) {
        // Check if customer with this email already exists for this user
        Optional<Customer> existingCustomer = customerRepository.findByEmailAndUser(request.getEmail(), user);
        if (existingCustomer.isPresent()) {
            throw new RuntimeException("Customer with this email already exists");
        }

        // Verify business belongs to user
        Business business = businessRepository.findByIdAndUserId(request.getBusinessId(), user.getId())
                .orElseThrow(() -> new RuntimeException("Business not found"));

        Customer customer = Customer.builder()
                .name(request.getName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .serviceDate(request.getServiceDate())
                .serviceType(request.getServiceType())
                .status(request.getStatus() != null ? request.getStatus() : Customer.CustomerStatus.COMPLETED)
                .tags(request.getTags() != null && !request.getTags().isEmpty() ?
                        request.getTags() :
                        List.of(Customer.CustomerTag.NEW_CUSTOMER))
                .notes(request.getNotes())
                .business(business)
                .user(user)
                .build();

        // NEW: Handle SMS consent fields
        handleSmsConsentFields(customer, request);

        Customer savedCustomer = customerRepository.save(customer);
        log.info("Created new customer: {} (SMS Opt-In: {})", savedCustomer.getName(), savedCustomer.getSmsOptIn());

        return convertToDto(savedCustomer);
    }

    public CustomerDto updateCustomer(User user, Long customerId, CreateCustomerRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        // Verify customer belongs to user
        if (!customer.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Customer not found");
        }

        // Verify business belongs to user if business is being changed
        if (request.getBusinessId() != null && !request.getBusinessId().equals(customer.getBusiness().getId())) {
            Business business = businessRepository.findByIdAndUserId(request.getBusinessId(), user.getId())
                    .orElseThrow(() -> new RuntimeException("Business not found"));
            customer.setBusiness(business);
        }

        // Update customer fields
        customer.setName(request.getName());
        customer.setEmail(request.getEmail());
        customer.setPhone(request.getPhone());
        customer.setServiceDate(request.getServiceDate());
        customer.setServiceType(request.getServiceType());
        customer.setStatus(request.getStatus());
        customer.setTags(request.getTags() != null && !request.getTags().isEmpty() ?
                request.getTags() :
                customer.getTags());
        customer.setNotes(request.getNotes());

        // NEW: Handle SMS consent field updates
        handleSmsConsentFields(customer, request);

        Customer savedCustomer = customerRepository.save(customer);
        log.info("Updated customer: {} (SMS Opt-In: {})", savedCustomer.getName(), savedCustomer.getSmsOptIn());

        return convertToDto(savedCustomer);
    }

    public void deleteCustomer(User user, Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));

        // Verify customer belongs to user
        if (!customer.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Customer not found");
        }

        customerRepository.delete(customer);
        log.info("Deleted customer: {} (ID: {})", customer.getName(), customerId);
    }

    public List<CustomerDto> searchCustomers(User user, String query) {
        List<Customer> customers = customerRepository.searchCustomers(user, query);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public CustomerStatsDto getCustomerStats(User user) {
        long totalCustomers = customerRepository.countByUser(user);
        long completedServices = customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.COMPLETED);
        long pendingServices = customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.PENDING);
        long cancelledServices = customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.CANCELLED);
        long repeatCustomers = customerRepository.countByUserAndTag(user, Customer.CustomerTag.REPEAT_CUSTOMER);
        long thisMonthCustomers = customerRepository.findCustomersThisMonth(user).size();

        // SMS-specific stats
        long customersWithPhone = customerRepository.countWithPhoneByUser(user);
        long smsOptedIn = customerRepository.countOptedInByUser(user);
        long smsOptedOut = customerRepository.countOptedOutByUser(user);
        long smsEligible = customerRepository.countSmsEligibleByUser(user);

        return CustomerStatsDto.builder()
                .totalCustomers(totalCustomers)
                .customersWithPhone(customersWithPhone)
                .completedServices(completedServices)
                .pendingServices(pendingServices)
                .cancelledServices(cancelledServices)
                .repeatCustomers(repeatCustomers)
                .thisMonthCustomers(thisMonthCustomers)
                .smsOptedIn(smsOptedIn)
                .smsOptedOut(smsOptedOut)
                .smsEligible(smsEligible)
                .needingSmsConsent(customersWithPhone - smsOptedIn - smsOptedOut)
                .build();
    }

    // NEW: Helper method to handle SMS consent fields
    private void handleSmsConsentFields(Customer customer, CreateCustomerRequest request) {
        // Handle SMS opt-in
        if (request.getSmsOptIn() != null && request.getSmsOptIn()) {
            // Customer is opting in
            Customer.SmsOptInMethod method = request.getSmsOptInMethod() != null ?
                    request.getSmsOptInMethod() : Customer.SmsOptInMethod.WEB_FORM;
            String source = request.getSmsOptInSource() != null ?
                    request.getSmsOptInSource() : "customer_management_form";

            customer.recordSmsOptIn(method, source);
            log.info("Customer {} opted in via {} from {}", customer.getName(), method, source);
        }
        else if (request.getSmsOptIn() != null && !request.getSmsOptIn()) {
            // Customer is explicitly opting out or removing opt-in
            customer.setSmsOptIn(false);
            // Only record opt-out if they were previously opted in
            if (customer.getSmsOptIn() != null && customer.getSmsOptIn()) {
                customer.recordSmsOptOut(Customer.SmsOptOutMethod.WEB_FORM);
                log.info("Customer {} opted out via web form", customer.getName());
            }
        }

        // Handle explicit SMS opt-out (for admin interface)
        if (request.getSmsOptOut() != null && request.getSmsOptOut()) {
            customer.recordSmsOptOut(Customer.SmsOptOutMethod.MANUAL);
            log.info("Customer {} manually opted out", customer.getName());
        }
    }

    private CustomerDto convertToDto(Customer customer) {
        return CustomerDto.builder()
                .id(customer.getId())
                .name(customer.getName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .serviceDate(customer.getServiceDate())
                .serviceType(customer.getServiceType())
                .status(customer.getStatus())
                .tags(customer.getTags())
                .notes(customer.getNotes())
                .business(CustomerDto.BusinessInfo.builder()
                        .id(customer.getBusiness().getId())
                        .name(customer.getBusiness().getName())
                        .industry(customer.getBusiness().getIndustry())
                        .build())
                .businessId(customer.getBusiness().getId()) // Legacy compatibility
                .businessName(customer.getBusiness().getName()) // Legacy compatibility
                .feedbackSubmitted(customer.getFeedbackSubmitted())
                .feedbackCount(customer.getFeedbackCount())
                .lastFeedbackDate(customer.getLastFeedbackDate())
                // NEW: SMS compliance fields
                .smsOptIn(customer.getSmsOptIn())
                .smsOptInMethod(customer.getSmsOptInMethod())
                .smsOptInTimestamp(customer.getSmsOptInTimestamp())
                .smsOptOut(customer.getSmsOptOut())
                .smsOptOutTimestamp(customer.getSmsOptOutTimestamp())
                .canReceiveSms(customer.canReceiveSms())
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .build();
    }
}