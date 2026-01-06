package com.reputul.backend.platform.service;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.EmailTemplateRepository;
import com.reputul.backend.platform.dto.integration.ZapierContactRequest;
import com.reputul.backend.platform.dto.integration.ZapierReviewRequestRequest;
import com.reputul.backend.platform.dto.integration.ZapierWebhookResponse;
import com.reputul.backend.platform.entity.IdempotencyKey;
import com.reputul.backend.platform.repository.IdempotencyKeyRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class ZapierWebhookService {

    private final CustomerRepository customerRepository;
    private final ReviewRequestRepository reviewRequestRepository;
    private final BusinessRepository businessRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    private static final int FREQUENCY_LIMIT_DAYS = 30;

    public ZapierWebhookService(
            CustomerRepository customerRepository,
            ReviewRequestRepository reviewRequestRepository,
            BusinessRepository businessRepository,
            EmailTemplateRepository emailTemplateRepository,
            IdempotencyKeyRepository idempotencyKeyRepository,
            ObjectMapper objectMapper) {
        this.customerRepository = customerRepository;
        this.reviewRequestRepository = reviewRequestRepository;
        this.businessRepository = businessRepository;
        this.emailTemplateRepository = emailTemplateRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle idempotent request processing
     * Returns cached response if idempotency key exists and hasn't expired
     */
    public Optional<ZapierWebhookResponse> checkIdempotency(String idempotencyKey, String requestPath) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return Optional.empty();
        }

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKeyAndExpiresAtAfter(
                idempotencyKey,
                LocalDateTime.now()
        );

        if (existing.isPresent() && existing.get().getRequestPath().equals(requestPath)) {
            log.info("Idempotency key found: {} - returning cached response", idempotencyKey);
            try {
                ZapierWebhookResponse response = objectMapper.readValue(
                        existing.get().getResponseBody(),
                        ZapierWebhookResponse.class
                );
                return Optional.of(response);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize cached response for idempotency key: {}", idempotencyKey, e);
                // Fall through to process request normally
            }
        }

        return Optional.empty();
    }

    /**
     * Store idempotency key with response for future duplicate detection
     */
    @Transactional
    public void storeIdempotencyKey(String idempotencyKey, String requestPath, Long organizationId,
                                    ZapierWebhookResponse response, int statusCode) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return;
        }

        try {
            String responseBody = objectMapper.writeValueAsString(response);

            IdempotencyKey key = IdempotencyKey.builder()
                    .key(idempotencyKey)
                    .organizationId(organizationId)
                    .requestPath(requestPath)
                    .responseStatus(statusCode)
                    .responseBody(responseBody)
                    .build();

            idempotencyKeyRepository.save(key);
            log.debug("Stored idempotency key: {}", idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response for idempotency key: {}", idempotencyKey, e);
        }
    }

    /**
     * Create or update a contact
     */
    @Transactional
    public ZapierWebhookResponse createContact(Long organizationId, ZapierContactRequest request) {
        log.info("Processing contact creation for organization {}: {}", organizationId, request.getCustomerName());

        // Validate contact method
        if (!request.hasContactMethod()) {
            return ZapierWebhookResponse.error(
                    "MISSING_CONTACT_METHOD",
                    "At least one contact method (email or phone) is required"
            );
        }

        // Resolve business
        Business business = resolveBusiness(organizationId, request.getBusinessId());
        if (business == null) {
            return ZapierWebhookResponse.error(
                    "BUSINESS_NOT_FOUND",
                    "Business not found or no default business configured for organization"
            );
        }

        // Find or create customer
        Customer customer = findOrCreateCustomer(business, request);
        boolean isNewCustomer = customer.getId() == null;

        // Update customer fields
        customer.setName(request.getCustomerName());
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            customer.setEmail(request.getEmail());
        }
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            customer.setPhone(request.getPhone());
        }
        if (request.getNotes() != null) {
            customer.setNotes(request.getNotes());
        }

        customer = customerRepository.save(customer);

        log.info("Contact {} successfully: id={}, name={}",
                isNewCustomer ? "created" : "updated",
                customer.getId(),
                customer.getName());

        return ZapierWebhookResponse.contactSuccess(
                customer.getId(),
                isNewCustomer,
                customer.getEmail(),
                customer.getPhone(),
                customer.getName()
        );
    }

    /**
     * Create contact and send review request (with frequency limiting)
     */
    @Transactional
    public ZapierWebhookResponse createReviewRequest(Long organizationId, ZapierReviewRequestRequest request) {
        log.info("Processing review request for organization {}: {}", organizationId, request.getCustomerName());

        // Validate contact method
        if (!request.hasContactMethod()) {
            return ZapierWebhookResponse.error(
                    "MISSING_CONTACT_METHOD",
                    "At least one contact method (email or phone) is required"
            );
        }

        // Resolve business
        Business business = resolveBusiness(organizationId, request.getBusinessId());
        if (business == null) {
            return ZapierWebhookResponse.error(
                    "BUSINESS_NOT_FOUND",
                    "Business not found or no default business configured for organization"
            );
        }

        // Find or create customer
        Customer customer = findOrCreateCustomerForReviewRequest(business, request);
        boolean isNewCustomer = customer.getId() == null;

        // Update customer fields
        customer.setName(request.getCustomerName());
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            customer.setEmail(request.getEmail());
        }
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            customer.setPhone(request.getPhone());
        }
        if (request.getNotes() != null) {
            customer.setNotes(request.getNotes());
        }

        customer = customerRepository.save(customer);

        // Check frequency limit (30-day rule)
        Optional<LocalDateTime> lastRequestTime = checkFrequencyLimit(
                business.getId(),
                customer.getEmail(),
                customer.getPhone()
        );

        if (lastRequestTime.isPresent()) {
            LocalDateTime retryAfter = lastRequestTime.get().plusDays(FREQUENCY_LIMIT_DAYS);
            long daysAgo = java.time.Duration.between(lastRequestTime.get(), LocalDateTime.now()).toDays();

            return ZapierWebhookResponse.error(
                    "RATE_LIMIT_EXCEEDED",
                    String.format("Contact was already sent a review request %d days ago. " +
                                    "Requests limited to once per %d days per contact.",
                            daysAgo, FREQUENCY_LIMIT_DAYS),
                    retryAfter
            );
        }

        // Get or create default email template for the business
        EmailTemplate emailTemplate = getDefaultEmailTemplate(business);
        if (emailTemplate == null) {
            return ZapierWebhookResponse.error(
                    "NO_EMAIL_TEMPLATE",
                    "No default email template found for business. Please create one first."
            );
        }

        // Determine delivery method
        ReviewRequest.DeliveryMethod deliveryMethod;
        String deliveryMethodStr = request.getDeliveryMethodOrDefault().toUpperCase();

        // Map "BOTH" to EMAIL (we'll create SMS separately if needed)
        if ("BOTH".equals(deliveryMethodStr)) {
            deliveryMethod = ReviewRequest.DeliveryMethod.EMAIL;
        } else {
            try {
                deliveryMethod = ReviewRequest.DeliveryMethod.valueOf(deliveryMethodStr);
            } catch (IllegalArgumentException e) {
                deliveryMethod = ReviewRequest.DeliveryMethod.EMAIL;
            }
        }

        // Generate review link (placeholder - customize based on your routing)
        String reviewLink = generateReviewLink(business, customer);

        // Create review request with correct field mapping
        ReviewRequest reviewRequest = ReviewRequest.builder()
                .customer(customer)
                .business(business)
                .emailTemplate(emailTemplate)
                .deliveryMethod(deliveryMethod)
                .recipientEmail(request.getEmail())  // FIXED: Use recipientEmail
                .recipientPhone(request.getPhone())  // FIXED: Use recipientPhone
                .subject("We'd love your feedback!")  // Default subject
                .emailBody("Please share your experience with us")  // Placeholder
                .smsMessage("Thanks for choosing us! Please leave a review: " + reviewLink)  // Placeholder
                .reviewLink(reviewLink)
                .status(ReviewRequest.RequestStatus.PENDING)
                .build();

        reviewRequest = reviewRequestRepository.save(reviewRequest);

        log.info("Review request created successfully: id={}, customer={}, business={}",
                reviewRequest.getId(),
                customer.getName(),
                business.getName());

        return ZapierWebhookResponse.reviewRequestSuccess(
                customer.getId(),
                isNewCustomer,
                customer.getEmail(),
                customer.getPhone(),
                customer.getName(),
                reviewRequest.getId(),
                reviewRequest.getStatus().toString(),
                reviewRequest.getCreatedAt().toLocalDateTime(),
                reviewRequest.getDeliveryMethod().toString()
        );
    }

    /**
     * Resolve business by ID or fallback to default
     */
    private Business resolveBusiness(Long organizationId, Long businessId) {
        if (businessId != null) {
            // Explicit business ID provided
            Optional<Business> business = businessRepository.findByIdAndOrganizationId(
                    businessId,
                    organizationId
            );
            return business.orElse(null);
        }

        // Fallback to default business
        Optional<Business> defaultBusiness = businessRepository.findDefaultByOrganizationId(organizationId);
        return defaultBusiness.orElse(null);
    }

    /**
     * Find existing customer by email or phone, or create new
     */
    private Customer findOrCreateCustomer(Business business, ZapierContactRequest request) {
        // Try to find by email first
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            Optional<Customer> existing = customerRepository.findByBusinessAndEmail(
                    business,
                    request.getEmail()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Try to find by phone
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            Optional<Customer> existing = customerRepository.findByBusinessAndPhone(
                    business,
                    request.getPhone()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Create new customer with required fields
        return Customer.builder()
                .business(business)
                .user(business.getUser())
                .status(Customer.CustomerStatus.COMPLETED)
                .serviceDate(LocalDate.now())
                .serviceType("Service")
                .build();
    }

    /**
     * Find existing customer by email or phone for review request
     */
    private Customer findOrCreateCustomerForReviewRequest(Business business, ZapierReviewRequestRequest request) {
        // Try to find by email first
        if (request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            Optional<Customer> existing = customerRepository.findByBusinessAndEmail(
                    business,
                    request.getEmail()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Try to find by phone
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            Optional<Customer> existing = customerRepository.findByBusinessAndPhone(
                    business,
                    request.getPhone()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        // Create new customer with required fields
        return Customer.builder()
                .business(business)
                .user(business.getUser())
                .status(Customer.CustomerStatus.COMPLETED)
                .serviceDate(LocalDate.now())
                .serviceType("Service")
                .build();
    }

    /**
     * Get default email template for business
     * Looks for default INITIAL_REQUEST template for the organization
     */
    private EmailTemplate getDefaultEmailTemplate(Business business) {
        Long organizationId = business.getOrganization() != null ?
                business.getOrganization().getId() : null;

        if (organizationId == null) {
            log.warn("Business {} has no organization - cannot find email template", business.getId());
            return null;
        }

        // Find default INITIAL_REQUEST template for the organization
        List<EmailTemplate> templates = emailTemplateRepository.findByOrgIdAndTypeAndIsDefaultTrue(
                organizationId,
                EmailTemplate.TemplateType.INITIAL_REQUEST
        );

        if (!templates.isEmpty()) {
            EmailTemplate template = templates.get(0);
            log.debug("Found default email template {} for organization {}", template.getId(), organizationId);
            return template;
        }

        // Fallback: find ANY active INITIAL_REQUEST template for the business owner
        if (business.getUser() != null) {
            List<EmailTemplate> userTemplates = emailTemplateRepository
                    .findByUserAndTypeAndIsActiveTrueOrderByCreatedAtDesc(
                            business.getUser(),
                            EmailTemplate.TemplateType.INITIAL_REQUEST
                    );

            if (!userTemplates.isEmpty()) {
                EmailTemplate template = userTemplates.get(0);
                log.debug("Using fallback email template {} for user {}", template.getId(), business.getUser().getId());
                return template;
            }
        }

        log.warn("No email template found for organization {} or user {}",
                organizationId,
                business.getUser() != null ? business.getUser().getId() : "null");
        return null;
    }

    /**
     * Generate review link for customer
     * Customize based on your review page routing
     */
    private String generateReviewLink(Business business, Customer customer) {
        // TODO: Customize this based on your actual review page URL structure
        // Example: https://app.reputul.com/review/{business-id}/{customer-id}
        return String.format("https://app.reputul.com/review/%d/%d",
                business.getId(),
                customer.getId());
    }

    /**
     * Check if contact has been sent a review request within the frequency limit period
     * Returns the timestamp of the last request if within limit, empty otherwise
     */
    private Optional<LocalDateTime> checkFrequencyLimit(Long businessId, String email, String phone) {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(FREQUENCY_LIMIT_DAYS);

        // Check by email - look at createdAt since sentAt might be null for pending requests
        if (email != null && !email.trim().isEmpty()) {
            Optional<ReviewRequest> recentByEmail = reviewRequestRepository
                    .findFirstByBusinessIdAndRecipientEmailAndCreatedAtAfterOrderByCreatedAtDesc(
                            businessId,
                            email,
                            OffsetDateTime.of(cutoffDate, ZoneOffset.UTC)
                    );
            if (recentByEmail.isPresent()) {
                return Optional.of(recentByEmail.get().getCreatedAt().toLocalDateTime());
            }
        }

        // Check by phone - look at createdAt since sentAt might be null for pending requests
        if (phone != null && !phone.trim().isEmpty()) {
            Optional<ReviewRequest> recentByPhone = reviewRequestRepository
                    .findFirstByBusinessIdAndRecipientPhoneAndCreatedAtAfterOrderByCreatedAtDesc(
                            businessId,
                            phone,
                            OffsetDateTime.of(cutoffDate, ZoneOffset.UTC)
                    );
            if (recentByPhone.isPresent()) {
                return Optional.of(recentByPhone.get().getCreatedAt().toLocalDateTime());
            }
        }

        return Optional.empty();
    }
}