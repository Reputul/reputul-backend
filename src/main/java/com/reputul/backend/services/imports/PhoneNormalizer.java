package com.reputul.backend.services.imports;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(PhoneNormalizer.class);
    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
    private static final String DEFAULT_REGION = "US"; // Default to US

    /**
     * Normalize phone number to E.164 format
     * @param phoneNumber Raw phone number string
     * @return Normalized E.164 format phone number or null if invalid
     */
    public String normalize(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return null;
        }

        try {
            // Parse the number
            Phonenumber.PhoneNumber parsedNumber = phoneUtil.parse(phoneNumber, DEFAULT_REGION);

            // Check if it's a valid number
            if (!phoneUtil.isValidNumber(parsedNumber)) {
                logger.debug("Invalid phone number: {}", phoneNumber);
                return null;
            }

            // Format as E.164
            return phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164);

        } catch (NumberParseException e) {
            logger.debug("Failed to parse phone number {}: {}", phoneNumber, e.getMessage());
            return null;
        }
    }

    /**
     * Validate if phone number is valid
     * @param phoneNumber Raw phone number string
     * @return true if valid, false otherwise
     */
    public boolean isValid(String phoneNumber) {
        return normalize(phoneNumber) != null;
    }
}