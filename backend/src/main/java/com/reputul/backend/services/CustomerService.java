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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final BusinessRepository businessRepository;

    public List<CustomerDto> getAllCustomersByUser(User user) {
        // Simplified: just get all customers for this user directly
        List<Customer> customers = customerRepository.findByUserOrderByCreatedAtDesc(user);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public List<CustomerDto> getCustomersByBusiness(User user, Long businessId) {
        Business business = businessRepository.findByIdAndOwnerId(businessId, user.getId())
                .orElseThrow(() -> new RuntimeException("Business not found"));

        List<Customer> customers = customerRepository.findByUserAndBusinessOrderByCreatedAtDesc(user, business);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public CustomerDto createCustomer(User user, CreateCustomerRequest request) {
        // Check if customer with this email already exists for this user
        Optional<Customer> existingCustomer = customerRepository.findByEmailAndUser(request.getEmail(), user);
        if (existingCustomer.isPresent()) {
            throw new RuntimeException("Customer with this email already exists");
        }

        // Verify business belongs to user
        Business business = businessRepository.findByIdAndOwnerId(request.getBusinessId(), user.getId())
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

        Customer savedCustomer = customerRepository.save(customer);
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
            Business business = businessRepository.findByIdAndOwnerId(request.getBusinessId(), user.getId())
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
        customer.setTags(request.getTags());
        customer.setNotes(request.getNotes());

        Customer savedCustomer = customerRepository.save(customer);
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

    public List<CustomerDto> searchCustomers(User user, String searchTerm) {
        List<Customer> customers = customerRepository.searchCustomers(user, searchTerm);
        return customers.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public CustomerStatsDto getCustomerStats(User user) {
        return CustomerStatsDto.builder()
                .totalCustomers(customerRepository.countByUser(user))
                .completedServices(customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.COMPLETED))
                .pendingServices(customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.PENDING))
                .cancelledServices(customerRepository.countByUserAndStatus(user, Customer.CustomerStatus.CANCELLED))
                .repeatCustomers(customerRepository.countByUserAndTag(user, Customer.CustomerTag.REPEAT_CUSTOMER))
                .thisMonthCustomers(customerRepository.findCustomersThisMonth(user).size())
                .build();
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
                .createdAt(customer.getCreatedAt())
                .updatedAt(customer.getUpdatedAt())
                .build();
    }
}