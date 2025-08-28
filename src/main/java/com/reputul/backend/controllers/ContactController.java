package com.reputul.backend.controllers;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.User;
import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.services.ContactService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/contacts")
@CrossOrigin(origins = {"http://localhost:3000", "https://reputul.com"})
public class ContactController {

    private static final Logger logger = LoggerFactory.getLogger(ContactController.class);

    @Autowired
    private ContactService contactService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BusinessRepository businessRepository;

    /**
     * Get all contacts with search and filtering
     */
    @GetMapping
    public ResponseEntity<Page<ContactDto>> getContacts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            Authentication authentication) {

        User user = getUserFromAuth(authentication);
        Long businessId = getUserBusinessId(user);

        if (businessId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Parse sort parameter
        String[] sortParts = sort.split(",");
        String sortField = sortParts[0];
        Sort.Direction direction = sortParts.length > 1 && "desc".equals(sortParts[1]) ?
                Sort.Direction.DESC : Sort.Direction.ASC;

        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(direction, sortField));

        Page<ContactDto> contacts = contactService.searchContacts(businessId, query, tag, pageable);

        return ResponseEntity.ok(contacts);
    }

    /**
     * Get single contact by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ContactDto> getContact(@PathVariable Long id, Authentication authentication) {
        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            ContactDto contact = contactService.getContact(businessId, id);
            return ResponseEntity.ok(contact);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Create new contact
     */
    @PostMapping
    public ResponseEntity<ContactDto> createContact(
            @Valid @RequestBody CreateContactRequest request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            ContactDto contact = contactService.createContact(businessId, request);

            logger.info("User {} created contact {} for business {}", user.getId(), contact.getId(), businessId);
            return ResponseEntity.status(HttpStatus.CREATED).body(contact);

        } catch (IllegalArgumentException e) {
            logger.warn("Failed to create contact: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update existing contact
     */
    @PutMapping("/{id}")
    public ResponseEntity<ContactDto> updateContact(
            @PathVariable Long id,
            @Valid @RequestBody UpdateContactRequest request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            ContactDto contact = contactService.updateContact(businessId, id, request);

            logger.info("User {} updated contact {} for business {}", user.getId(), id, businessId);
            return ResponseEntity.ok(contact);

        } catch (IllegalArgumentException e) {
            logger.warn("Failed to update contact {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Delete contact
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContact(@PathVariable Long id, Authentication authentication) {
        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            contactService.deleteContact(businessId, id);

            logger.info("User {} deleted contact {} for business {}", user.getId(), id, businessId);
            return ResponseEntity.noContent().build();

        } catch (IllegalArgumentException e) {
            logger.warn("Failed to delete contact {}: {}", id, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Prepare CSV import - upload and parse CSV
     */
    @PostMapping("/bulk/import/prepare")
    public ResponseEntity<?> prepareCsvImport(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No active business"));
            }

            CsvImportPrepareResponse response = contactService.prepareCsvImport(businessId, user.getId(), file);

            logger.info("User {} prepared CSV import for business {}, {} rows",
                    user.getId(), businessId, response.getTotalRows());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("CSV import prepare failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            logger.error("CSV import prepare failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to process CSV file"));
        }
    }

    /**
     * Commit CSV import - process and save contacts
     */
    @PostMapping("/bulk/import/commit")
    public ResponseEntity<?> commitCsvImport(
            @Valid @RequestBody CsvImportCommitRequest request,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "No active business"));
            }

            CsvImportCommitResponse response = contactService.commitCsvImport(businessId, user.getId(), request);

            logger.info("User {} committed CSV import for business {}: {} inserted, {} updated, {} skipped, {} errors",
                    user.getId(), businessId, response.getInsertedCount(), response.getUpdatedCount(),
                    response.getSkippedCount(), response.getErrorCount());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("CSV import commit failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("CSV import commit failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Import failed: " + e.getMessage()));
        }
    }

    /**
     * Export contacts to CSV
     */
    @GetMapping("/export.csv")
    public ResponseEntity<String> exportContacts(
            @RequestParam(required = false) String tag,
            Authentication authentication) {

        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            String csvData = contactService.exportContactsCsv(businessId, tag);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", "contacts_export.csv");

            logger.info("User {} exported contacts for business {} (tag: {})", user.getId(), businessId, tag);
            return ResponseEntity.ok().headers(headers).body(csvData);

        } catch (Exception e) {
            logger.error("Contact export failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get contact statistics for dashboard
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getContactStats(Authentication authentication) {
        try {
            User user = getUserFromAuth(authentication);
            Long businessId = getUserBusinessId(user);

            if (businessId == null) {
                return ResponseEntity.badRequest().build();
            }

            // For now, return basic stats - can be expanded later
            Page<ContactDto> allContacts = contactService.searchContacts(businessId, null, null,
                    PageRequest.of(0, 1));

            Map<String, Object> stats = Map.of(
                    "totalContacts", allContacts.getTotalElements(),
                    "contactsThisMonth", 0, // TODO: Implement
                    "contactsWithPhone", 0, // TODO: Implement
                    "contactsWithEmail", 0  // TODO: Implement
            );

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            logger.error("Failed to get contact stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // Helper Methods

    /**
     * Extract user from authentication context
     */
    private User getUserFromAuth(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalArgumentException("Authentication required");
        }

        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    /**
     * Get the business ID for the authenticated user
     * FIXED: Properly handle Optional<Business> return type
     */
    private Long getUserBusinessId(User user) {
        return businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .map(Business::getId)
                .orElse(null);
    }

    /**
     * Global exception handler for this controller
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("Invalid request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception e) {
        logger.error("Unexpected error in ContactController", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred"));
    }
}