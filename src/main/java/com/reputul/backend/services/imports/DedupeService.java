package com.reputul.backend.services.imports;

import com.reputul.backend.models.Contact;
import com.reputul.backend.repositories.ContactRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DedupeService {

    @Autowired
    private ContactRepository contactRepository;

    public static class DedupeResult {
        private final Contact existingContact;
        private final String matchReason;

        public DedupeResult(Contact existingContact, String matchReason) {
            this.existingContact = existingContact;
            this.matchReason = matchReason;
        }

        public Contact getExistingContact() { return existingContact; }
        public String getMatchReason() { return matchReason; }
    }

    /**
     * Find potential duplicates for a contact
     */
    public DedupeResult findDuplicate(Long businessId, String name, String email, String phone, LocalDate lastJobDate) {
        // Check email match first (strongest signal)
        if (email != null && !email.trim().isEmpty()) {
            Optional<Contact> emailMatch = contactRepository.findByBusinessIdAndEmail(businessId, email.trim().toLowerCase());
            if (emailMatch.isPresent()) {
                return new DedupeResult(emailMatch.get(), "email");
            }
        }

        // Check phone match
        if (phone != null && !phone.trim().isEmpty()) {
            Optional<Contact> phoneMatch = contactRepository.findByBusinessIdAndPhone(businessId, phone);
            if (phoneMatch.isPresent()) {
                return new DedupeResult(phoneMatch.get(), "phone");
            }
        }

        // Check name + date match (within 3 days)
        if (name != null && !name.trim().isEmpty() && lastJobDate != null) {
            LocalDate startDate = lastJobDate.minusDays(3);
            LocalDate endDate = lastJobDate.plusDays(3);

            List<Contact> nameMatches = contactRepository.findByBusinessIdAndNameAndLastJobDateBetween(
                    businessId, name.trim(), startDate, endDate);

            if (!nameMatches.isEmpty()) {
                return new DedupeResult(nameMatches.get(0), "name_date");
            }
        }

        return null; // No duplicate found
    }

    /**
     * Merge contact data according to merge policy
     */
    public Contact mergeContacts(Contact existing, Contact incoming) {
        // Name: prefer non-empty incoming; else keep existing
        if (incoming.getName() != null && !incoming.getName().trim().isEmpty()) {
            existing.setName(incoming.getName());
        }

        // Email: prefer non-empty incoming; else keep existing
        if (incoming.getEmail() != null && !incoming.getEmail().trim().isEmpty()) {
            existing.setEmail(incoming.getEmail());
        }

        // Phone: prefer non-empty incoming; else keep existing
        if (incoming.getPhone() != null && !incoming.getPhone().trim().isEmpty()) {
            existing.setPhone(incoming.getPhone());
        }

        // LastJobDate: keep max(existing, incoming)
        if (incoming.getLastJobDate() != null) {
            if (existing.getLastJobDate() == null || incoming.getLastJobDate().isAfter(existing.getLastJobDate())) {
                existing.setLastJobDate(incoming.getLastJobDate());
            }
        }

        // Tags: union (case-insensitive unique)
        if (incoming.getTags() != null && !incoming.getTags().isEmpty()) {
            existing.addTags(incoming.getTags());
        }

        // Consent: if incoming is true, set true; if false, set false; if null, keep existing
        if (incoming.getSmsConsent() != null) {
            existing.setSmsConsent(incoming.getSmsConsent());
        }

        if (incoming.getEmailConsent() != null) {
            existing.setEmailConsent(incoming.getEmailConsent());
        }

        return existing;
    }

    /**
     * Batch find duplicates for multiple contacts
     */
    public Map<String, DedupeResult> batchFindDuplicates(Long businessId, List<Map<String, Object>> contactData) {
        Map<String, DedupeResult> results = new HashMap<>();

        // Collect all emails and phones for batch queries
        Set<String> emails = contactData.stream()
                .map(data -> (String) data.get("email"))
                .filter(Objects::nonNull)
                .map(email -> email.trim().toLowerCase())
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toSet());

        Set<String> phones = contactData.stream()
                .map(data -> (String) data.get("phone"))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(phone -> !phone.isEmpty())
                .collect(Collectors.toSet());

        // Batch query existing contacts
        Map<String, Contact> emailMatches = new HashMap<>();
        if (!emails.isEmpty()) {
            List<Contact> existingByEmail = contactRepository.findByBusinessIdAndEmailIn(businessId, new ArrayList<>(emails));
            for (Contact contact : existingByEmail) {
                emailMatches.put(contact.getEmail(), contact);
            }
        }

        Map<String, Contact> phoneMatches = new HashMap<>();
        if (!phones.isEmpty()) {
            List<Contact> existingByPhone = contactRepository.findByBusinessIdAndPhoneIn(businessId, new ArrayList<>(phones));
            for (Contact contact : existingByPhone) {
                phoneMatches.put(contact.getPhone(), contact);
            }
        }

        // Check each contact for duplicates
        for (int i = 0; i < contactData.size(); i++) {
            Map<String, Object> data = contactData.get(i);
            String key = "row_" + i;

            String email = (String) data.get("email");
            String phone = (String) data.get("phone");
            String name = (String) data.get("name");
            LocalDate lastJobDate = (LocalDate) data.get("lastJobDate");

            // Check email match
            if (email != null && !email.trim().isEmpty()) {
                Contact match = emailMatches.get(email.trim().toLowerCase());
                if (match != null) {
                    results.put(key, new DedupeResult(match, "email"));
                    continue;
                }
            }

            // Check phone match
            if (phone != null && !phone.trim().isEmpty()) {
                Contact match = phoneMatches.get(phone);
                if (match != null) {
                    results.put(key, new DedupeResult(match, "phone"));
                    continue;
                }
            }

            // Name+date match (this still requires individual queries for now)
            if (name != null && !name.trim().isEmpty() && lastJobDate != null) {
                LocalDate startDate = lastJobDate.minusDays(3);
                LocalDate endDate = lastJobDate.plusDays(3);

                List<Contact> nameMatches = contactRepository.findByBusinessIdAndNameAndLastJobDateBetween(
                        businessId, name.trim(), startDate, endDate);

                if (!nameMatches.isEmpty()) {
                    results.put(key, new DedupeResult(nameMatches.get(0), "name_date"));
                }
            }
        }

        return results;
    }
}