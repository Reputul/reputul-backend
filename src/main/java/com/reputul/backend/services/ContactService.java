// ContactService.java
package com.reputul.backend.services;

import com.reputul.backend.dto.*;
import com.reputul.backend.models.Contact;
import com.reputul.backend.models.ImportJob;
import com.reputul.backend.repositories.ContactRepository;
import com.reputul.backend.repositories.ImportJobRepository;
import com.reputul.backend.services.imports.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContactService {

    private static final Logger logger = LoggerFactory.getLogger(ContactService.class);

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ImportJobRepository importJobRepository;

    @Autowired
    private PhoneNormalizer phoneNormalizer;

    @Autowired
    private CsvParser csvParser;

    @Autowired
    private UploadCache uploadCache;

    @Autowired
    private DedupeService dedupeService;

    private static final int MAX_CSV_ROWS = 10000;
    private static final int RATE_LIMIT_IMPORTS_PER_HOUR = 5;
    private static final int SAMPLE_ROWS_LIMIT = 10;

    // CRUD Operations

    /**
     * Create a new contact
     */
    @Transactional
    public ContactDto createContact(Long businessId, CreateContactRequest request) {
        validateCreateRequest(request);

        Contact contact = new Contact();
        contact.setBusinessId(businessId);
        populateContactFromRequest(contact, request);

        // Check for duplicates before saving
        DedupeService.DedupeResult duplicate = dedupeService.findDuplicate(
                businessId, contact.getName(), contact.getEmail(), contact.getPhone(), contact.getLastJobDate());

        if (duplicate != null) {
            throw new IllegalArgumentException("Contact already exists: " + duplicate.getMatchReason() + " match found");
        }

        contact = contactRepository.save(contact);
        logger.info("Created contact {} for business {}", contact.getId(), businessId);

        return convertToDto(contact);
    }

    /**
     * Update an existing contact
     */
    @Transactional
    public ContactDto updateContact(Long businessId, Long contactId, UpdateContactRequest request) {
        validateUpdateRequest(request);

        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        // Ensure contact belongs to the business
        if (!contact.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Contact not found in organization");
        }

        populateContactFromRequest(contact, request);
        contact = contactRepository.save(contact);

        logger.info("Updated contact {} for business {}", contact.getId(), businessId);
        return convertToDto(contact);
    }

    /**
     * Delete a contact
     */
    @Transactional
    public void deleteContact(Long businessId, Long contactId) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        if (!contact.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Contact not found in organization");
        }

        contactRepository.delete(contact);
        logger.info("Deleted contact {} for business {}", contactId, businessId);
    }

    /**
     * Get contact by ID
     */
    public ContactDto getContact(Long businessId, Long contactId) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        if (!contact.getBusinessId().equals(businessId)) {
            throw new IllegalArgumentException("Contact not found in organization");
        }

        return convertToDto(contact);
    }

    /**
     * Search and filter contacts with pagination
     */
    public Page<ContactDto> searchContacts(Long businessId, String query, String tag, Pageable pageable) {
        Page<Contact> contacts;

        boolean hasQuery = query != null && !query.trim().isEmpty();
        boolean hasTag = tag != null && !tag.trim().isEmpty();

        if (hasQuery && hasTag) {
            contacts = contactRepository.findByBusinessIdAndQueryAndTag(businessId, query.trim(), tag.trim(), pageable);
        } else if (hasQuery) {
            contacts = contactRepository.findByBusinessIdAndQuery(businessId, query.trim(), pageable);
        } else if (hasTag) {
            contacts = contactRepository.findByBusinessIdAndTag(businessId, tag.trim(), pageable);
        } else {
            contacts = contactRepository.findByBusinessId(businessId, pageable);
        }

        return contacts.map(this::convertToDto);
    }

    // CSV Import Operations

    /**
     * Prepare CSV import - parse and validate
     */
    public CsvImportPrepareResponse prepareCsvImport(Long businessId, Long userId, MultipartFile file) throws IOException {
        // Rate limiting check
        checkImportRateLimit(userId);

        // Validate file
        validateCsvFile(file);

        try {
            // Parse CSV
            CsvParser.ParsedCsvData parsedData = csvParser.parseCsv(file.getInputStream());

            if (parsedData.getRows().size() > MAX_CSV_ROWS) {
                throw new IllegalArgumentException("CSV file too large. Maximum " + MAX_CSV_ROWS + " rows allowed.");
            }

            // Store in cache
            String uploadId = uploadCache.store(parsedData, file.getOriginalFilename());

            // Prepare response
            CsvImportPrepareResponse response = new CsvImportPrepareResponse(uploadId);
            response.setDetectedDelimiter(parsedData.getDetectedDelimiter());
            response.setDetectedHeaders(parsedData.getHeaders());
            response.setSuggestedMapping(csvParser.suggestMapping(parsedData.getHeaders()));
            response.setTotalRows(parsedData.getRows().size());

            // Sample rows (first N rows)
            List<Map<String, Object>> sampleRows = parsedData.getRows().stream()
                    .limit(SAMPLE_ROWS_LIMIT)
                    .map(row -> new HashMap<String, Object>(row))
                    .collect(Collectors.toList());
            response.setSampleRows(sampleRows);

            // Validate and dedupe
            validateAndDedupeRows(businessId, parsedData, response);

            logger.info("Prepared CSV import for business {}, {} rows", businessId, parsedData.getRows().size());
            return response;

        } catch (Exception e) {
            logger.error("Failed to prepare CSV import for business " + businessId, e);
            throw new IOException("Failed to process CSV file: " + e.getMessage());
        }
    }

    /**
     * Commit CSV import
     */
    @Transactional
    public CsvImportCommitResponse commitCsvImport(Long businessId, Long userId, CsvImportCommitRequest request) {
        // Retrieve cached data
        UploadCache.CachedUpload cached = uploadCache.get(request.getUploadId());
        if (cached == null) {
            throw new IllegalArgumentException("Upload session expired or not found");
        }

        // Create import job for auditing
        ImportJob importJob = new ImportJob(businessId, userId, cached.getOriginalFilename());
        importJob.setTotalRows(cached.getParsedData().getRows().size());
        importJob = importJobRepository.save(importJob);

        CsvImportCommitResponse response = new CsvImportCommitResponse("success", 0);
        response.setImportJobId(importJob.getId());

        try {
            // Process rows
            List<Map<String, String>> rows = cached.getParsedData().getRows();
            List<Map<String, Object>> processedContacts = new ArrayList<>();
            List<Map<String, Object>> errors = new ArrayList<>();

            // Convert and validate each row
            for (int i = 0; i < rows.size(); i++) {
                try {
                    Map<String, Object> contactData = convertRowToContact(rows.get(i), request.getColumnMap(), request.getDefaultTags());
                    if (contactData != null) {
                        contactData.put("rowIndex", i);
                        processedContacts.add(contactData);
                    }
                } catch (Exception e) {
                    errors.add(Map.of(
                            "rowNumber", i + 2, // +2 for header and 0-based index
                            "error", e.getMessage(),
                            "rowData", rows.get(i)
                    ));
                }
            }

            // Batch dedupe check
            Map<String, DedupeService.DedupeResult> dedupeResults = dedupeService.batchFindDuplicates(businessId, processedContacts);

            // Process each contact
            int inserted = 0, updated = 0, skipped = 0;

            for (Map<String, Object> contactData : processedContacts) {
                try {
                    String rowKey = "row_" + contactData.get("rowIndex");
                    DedupeService.DedupeResult dedupeResult = dedupeResults.get(rowKey);

                    if (dedupeResult != null && "skip-duplicates".equals(request.getMode())) {
                        skipped++;
                        continue;
                    }

                    if (dedupeResult != null && "upsert".equals(request.getMode())) {
                        // Update existing
                        Contact existingContact = dedupeResult.getExistingContact();
                        Contact incomingContact = createContactFromData(contactData, businessId);
                        Contact merged = dedupeService.mergeContacts(existingContact, incomingContact);
                        contactRepository.save(merged);
                        updated++;
                    } else {
                        // Insert new
                        Contact newContact = createContactFromData(contactData, businessId);
                        contactRepository.save(newContact);
                        inserted++;
                    }
                } catch (Exception e) {
                    errors.add(Map.of(
                            "rowNumber", (Integer)contactData.get("rowIndex") + 2,
                            "error", e.getMessage(),
                            "rowData", contactData
                    ));
                }
            }

            // Update response
            response.setTotalProcessed(processedContacts.size());
            response.setInsertedCount(inserted);
            response.setUpdatedCount(updated);
            response.setSkippedCount(skipped);
            response.setErrorCount(errors.size());

            // Sample errors (first 10)
            response.setSampleErrors(errors.stream().limit(10).collect(Collectors.toList()));

            // Update import job
            importJob.setInsertedCount(inserted);
            importJob.setUpdatedCount(updated);
            importJob.setSkippedCount(skipped);
            importJob.setErrorCount(errors.size());
            importJob.setStatus(ImportJob.Status.COMPLETED);
            importJob.setErrorDetails(errors);
            importJobRepository.save(importJob);

            // Clean up cache
            uploadCache.remove(request.getUploadId());

            logger.info("Completed CSV import for business {}: {} inserted, {} updated, {} skipped, {} errors",
                    businessId, inserted, updated, skipped, errors.size());

            return response;

        } catch (Exception e) {
            // Update import job as failed
            importJob.setStatus(ImportJob.Status.FAILED);
            importJob.setErrorDetails(List.of(Map.of("error", e.getMessage())));
            importJobRepository.save(importJob);

            logger.error("Failed CSV import for org " + businessId, e);
            throw new RuntimeException("Import failed: " + e.getMessage());
        }
    }

    /**
     * Export contacts to CSV
     */
    public String exportContactsCsv(Long businessId, String tag) {
        List<Contact> contacts;
        if (tag != null && !tag.trim().isEmpty()) {
            contacts = contactRepository.findAllByBusinessIdAndTagForExport(businessId, tag.trim());
        } else {
            contacts = contactRepository.findAllByBusinessIdForExport(businessId);
        }

        StringBuilder csv = new StringBuilder();
        csv.append("name,email,phone,last_job_date,tags,sms_consent,email_consent,created_at\n");

        for (Contact contact : contacts) {
            csv.append(escapeCsvValue(contact.getName())).append(",");
            csv.append(escapeCsvValue(contact.getEmail())).append(",");
            csv.append(escapeCsvValue(contact.getPhone())).append(",");
            csv.append(contact.getLastJobDate() != null ? contact.getLastJobDate().toString() : "").append(",");
            csv.append(escapeCsvValue(String.join(";", contact.getTags() != null ? contact.getTags() : new HashSet<>()))).append(",");
            csv.append(contact.getSmsConsent() != null ? contact.getSmsConsent().toString() : "").append(",");
            csv.append(contact.getEmailConsent() != null ? contact.getEmailConsent().toString() : "").append(",");
            csv.append(contact.getCreatedAt() != null ? contact.getCreatedAt().toString() : "").append("\n");
        }

        logger.info("Exported {} contacts for business {}", contacts.size(), businessId);
        return csv.toString();
    }

    // Helper Methods

    private void validateCreateRequest(CreateContactRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }

        if (request.getPhone() != null && !phoneNormalizer.isValid(request.getPhone())) {
            throw new IllegalArgumentException("Invalid phone number format");
        }
    }

    private void validateUpdateRequest(UpdateContactRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required");
        }

        if (request.getPhone() != null && !phoneNormalizer.isValid(request.getPhone())) {
            throw new IllegalArgumentException("Invalid phone number format");
        }
    }

    private void populateContactFromRequest(Contact contact, CreateContactRequest request) {
        contact.setName(request.getName().trim());
        contact.setEmail(request.getEmail());

        // Normalize phone number
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            contact.setPhone(phoneNormalizer.normalize(request.getPhone()));
        }

        contact.setLastJobDate(request.getLastJobDate());

        if (request.getTags() != null) {
            contact.setTags(request.getTags());
        }

        contact.setSmsConsent(request.getSmsConsent());
        contact.setEmailConsent(request.getEmailConsent());
    }

    private void populateContactFromRequest(Contact contact, UpdateContactRequest request) {
        contact.setName(request.getName().trim());
        contact.setEmail(request.getEmail());

        // Normalize phone number
        if (request.getPhone() != null && !request.getPhone().trim().isEmpty()) {
            contact.setPhone(phoneNormalizer.normalize(request.getPhone()));
        }

        contact.setLastJobDate(request.getLastJobDate());

        if (request.getTags() != null) {
            contact.setTags(request.getTags());
        }

        contact.setSmsConsent(request.getSmsConsent());
        contact.setEmailConsent(request.getEmailConsent());
    }

    private ContactDto convertToDto(Contact contact) {
        ContactDto dto = new ContactDto();
        dto.setId(contact.getId());
        dto.setBusinessId(contact.getBusinessId());
        dto.setName(contact.getName());
        dto.setEmail(contact.getEmail());
        dto.setPhone(contact.getPhone());
        dto.setLastJobDate(contact.getLastJobDate());
        dto.setTags(contact.getTags());
        dto.setSmsConsent(contact.getSmsConsent());
        dto.setEmailConsent(contact.getEmailConsent());
        dto.setCreatedAt(contact.getCreatedAt());
        dto.setUpdatedAt(contact.getUpdatedAt());
        return dto;
    }

    private void checkImportRateLimit(Long userId) {
        OffsetDateTime oneHourAgo = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1);
        long recentImports = importJobRepository.countByUserIdAndCreatedAtAfter(userId, oneHourAgo);

        if (recentImports >= RATE_LIMIT_IMPORTS_PER_HOUR) {
            throw new IllegalArgumentException("Rate limit exceeded. Maximum " + RATE_LIMIT_IMPORTS_PER_HOUR + " imports per hour.");
        }
    }

    private void validateCsvFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty");
        }

        if (file.getSize() > 10 * 1024 * 1024) { // 10MB limit
            throw new IllegalArgumentException("File too large. Maximum 10MB allowed.");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Only CSV files are allowed");
        }
    }

    private void validateAndDedupeRows(Long businessId, CsvParser.ParsedCsvData parsedData, CsvImportPrepareResponse response) {
        List<CsvImportPrepareResponse.CsvRowValidation> validations = new ArrayList<>();
        List<CsvImportPrepareResponse.DedupeCandidate> dedupes = new ArrayList<>();
        int validCount = 0;

        for (int i = 0; i < parsedData.getRows().size(); i++) {
            Map<String, String> row = parsedData.getRows().get(i);
            List<String> rowErrors = new ArrayList<>();

            // Validate required fields
            String name = row.get("name");
            if (name == null || name.trim().isEmpty()) {
                rowErrors.add("Name is required");
            }

            // Validate phone if present
            String phone = row.get("phone");
            if (phone != null && !phone.trim().isEmpty() && !phoneNormalizer.isValid(phone)) {
                rowErrors.add("Invalid phone number format");
            }

            // Validate email if present
            String email = row.get("email");
            if (email != null && !email.trim().isEmpty() && !email.contains("@")) {
                rowErrors.add("Invalid email format");
            }

            boolean isValid = rowErrors.isEmpty();
            if (isValid) {
                validCount++;

                // Check for duplicates
                String normalizedPhone = phone != null ? phoneNormalizer.normalize(phone) : null;
                String normalizedEmail = email != null ? email.trim().toLowerCase() : null;
                LocalDate jobDate = csvParser.parseDate(row.get("lastJobDate"));

                DedupeService.DedupeResult duplicate = dedupeService.findDuplicate(
                        businessId, name, normalizedEmail, normalizedPhone, jobDate);

                if (duplicate != null) {
                    CsvImportPrepareResponse.DedupeCandidate candidate = new CsvImportPrepareResponse.DedupeCandidate(
                            i + 2, duplicate.getMatchReason(), duplicate.getExistingContact().getId(),
                            duplicate.getExistingContact().getName());
                    candidate.setExistingContactEmail(duplicate.getExistingContact().getEmail());
                    candidate.setExistingContactPhone(duplicate.getExistingContact().getPhone());
                    candidate.setIncomingData(new HashMap<>(row));
                    dedupes.add(candidate);
                }
            }

            validations.add(new CsvImportPrepareResponse.CsvRowValidation(i + 2, isValid, rowErrors, new HashMap<>(row)));
        }

        response.setValidationResults(validations);
        response.setDedupeResults(dedupes);
        response.setValidRows(validCount);
        response.setInvalidRows(parsedData.getRows().size() - validCount);
    }

    private Map<String, Object> convertRowToContact(Map<String, String> row, Map<String, String> columnMap, List<String> defaultTags) {
        Map<String, Object> contactData = new HashMap<>();

        // Map fields using column mapping
        for (Map.Entry<String, String> mapping : columnMap.entrySet()) {
            String field = mapping.getKey();
            String column = mapping.getValue();
            String value = row.get(column);

            if (value == null || value.trim().isEmpty()) {
                continue;
            }

            switch (field) {
                case "name":
                    contactData.put("name", value.trim());
                    break;
                case "email":
                    contactData.put("email", value.trim().toLowerCase());
                    break;
                case "phone":
                    String normalizedPhone = phoneNormalizer.normalize(value);
                    if (normalizedPhone != null) {
                        contactData.put("phone", normalizedPhone);
                    }
                    break;
                case "lastJobDate":
                    LocalDate date = csvParser.parseDate(value);
                    if (date != null) {
                        contactData.put("lastJobDate", date);
                    }
                    break;
                case "tags":
                    Set<String> tags = csvParser.parseTags(value);
                    if (!tags.isEmpty()) {
                        contactData.put("tags", tags);
                    }
                    break;
            }
        }

        // Add default tags
        if (defaultTags != null && !defaultTags.isEmpty()) {
            @SuppressWarnings("unchecked")
            Set<String> existingTags = (Set<String>) contactData.getOrDefault("tags", new HashSet<String>());
            existingTags.addAll(defaultTags.stream().map(String::toLowerCase).collect(Collectors.toSet()));
            contactData.put("tags", existingTags);
        }

        // Validate required fields
        if (!contactData.containsKey("name")) {
            throw new IllegalArgumentException("Name is required");
        }

        return contactData;
    }

    private Contact createContactFromData(Map<String, Object> contactData, Long businessId) {
        Contact contact = new Contact();
        contact.setBusinessId(businessId);
        contact.setName((String) contactData.get("name"));
        contact.setEmail((String) contactData.get("email"));
        contact.setPhone((String) contactData.get("phone"));
        contact.setLastJobDate((LocalDate) contactData.get("lastJobDate"));

        @SuppressWarnings("unchecked")
        Set<String> tags = (Set<String>) contactData.get("tags");
        if (tags != null) {
            contact.setTags(tags);
        }

        return contact;
    }

    private String escapeCsvValue(String value) {
        if (value == null) {
            return "";
        }

        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}