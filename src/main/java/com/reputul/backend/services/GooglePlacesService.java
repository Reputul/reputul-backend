package com.reputul.backend.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.models.Business;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Places API Service
 *
 * Handles auto-detection of Place IDs and generation of Google review URLs.
 * Implements the Reputul recommended approach:
 * 1. Auto-detect Place ID using Google Places API
 * 2. Generate direct review URL (https://search.google.com/local/writereview?placeid=PLACE_ID)
 * 3. Fall back to search URL if Place ID unavailable
 *
 * @author Reputul Team
 * @since 2025-01-10
 */
@Service
@Slf4j
public class GooglePlacesService {

    @Value("${google.places.api.key}")
    private String apiKey;

    private static final String PLACES_API_BASE_URL = "https://places.googleapis.com/v1/places:searchText";
    private static final String PLACE_DETAILS_URL = "https://places.googleapis.com/v1/places";

    // URL templates
    private static final String REVIEW_URL_TEMPLATE = "https://search.google.com/local/writereview?placeid=%s";
    private static final String MAPS_URL_TEMPLATE = "https://www.google.com/maps/place/?q=place_id:%s";
    private static final String SEARCH_URL_TEMPLATE = "https://www.google.com/search?q=%s";

    // Regex pattern for g.page short URLs
    private static final Pattern G_PAGE_PATTERN = Pattern.compile("https://g\\.page/r/([A-Za-z0-9_-]+)(?:/review)?");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GooglePlacesService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Auto-detect Place ID for a business using Google Places API
     *
     * @param business The business to lookup
     * @return PlacesLookupResult with Place ID and details, or null if not found
     */
    public PlacesLookupResult autoDetectPlaceId(Business business) {
        if (business == null || business.getName() == null || business.getAddress() == null) {
            log.warn("Cannot auto-detect Place ID: business name or address is missing");
            return null;
        }

        try {
            // Build search query: "Business Name, Address"
            String searchQuery = buildSearchQuery(business);
            log.info("Auto-detecting Place ID for: {}", searchQuery);

            // Call Google Places API (New)
            String requestBody = String.format(
                    """
                    {
                      "textQuery": "%s",
                      "maxResultCount": 1
                    }
                    """,
                    searchQuery.replace("\"", "\\\"")
            );

            String url = UriComponentsBuilder.fromHttpUrl(PLACES_API_BASE_URL)
                    .build()
                    .toUriString();

            // Create headers with API key and field mask
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.set("X-Goog-Api-Key", apiKey);
            headers.set("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.types");

            org.springframework.http.HttpEntity<String> request = new org.springframework.http.HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url,
                    request,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parsePlacesResponse(response.getBody());
            } else {
                log.warn("Google Places API returned non-success status: {}", response.getStatusCode());
                return null;
            }

        } catch (Exception e) {
            log.error("Error auto-detecting Place ID for business: {}", business.getName(), e);
            return null;
        }
    }

    /**
     * Parse Google Places API response
     */
    private PlacesLookupResult parsePlacesResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode places = root.get("places");

            if (places == null || !places.isArray() || places.isEmpty()) {
                log.info("No places found in Google Places API response");
                return null;
            }

            // Get first result
            JsonNode place = places.get(0);
            String placeId = place.get("id").asText();
            String displayName = place.has("displayName") ?
                    place.get("displayName").get("text").asText() : null;
            String formattedAddress = place.has("formattedAddress") ?
                    place.get("formattedAddress").asText() : null;

            // Get types (if available)
            List<String> types = new ArrayList<>();
            if (place.has("types")) {
                place.get("types").forEach(type -> types.add(type.asText()));
            }

            PlacesLookupResult result = new PlacesLookupResult();
            result.setPlaceId(placeId);
            result.setPlaceName(displayName);
            result.setFormattedAddress(formattedAddress);
            result.setTypes(types);
            result.setSuccess(true);

            log.info("Successfully found Place ID: {} for {}", placeId, displayName);
            return result;

        } catch (Exception e) {
            log.error("Error parsing Google Places API response", e);
            return null;
        }
    }

    /**
     * Build search query from business details
     */
    private String buildSearchQuery(Business business) {
        StringBuilder query = new StringBuilder();

        // Add business name
        if (business.getName() != null) {
            query.append(business.getName());
        }

        // Add address
        if (business.getAddress() != null && !business.getAddress().trim().isEmpty()) {
            if (query.length() > 0) query.append(", ");
            query.append(business.getAddress());
        }

        // Add city if available
        // Note: Assuming address contains full address. Adjust if you have separate city field

        return query.toString();
    }

    /**
     * Generate Google review URL from Place ID
     *
     * @param placeId The Google Place ID
     * @return Direct review URL
     */
    public String generateReviewUrl(String placeId) {
        if (placeId == null || placeId.trim().isEmpty()) {
            return null;
        }
        return String.format(REVIEW_URL_TEMPLATE, placeId);
    }

    /**
     * Generate Google Maps URL from Place ID (backup)
     *
     * @param placeId The Google Place ID
     * @return Maps URL
     */
    public String generateMapsUrl(String placeId) {
        if (placeId == null || placeId.trim().isEmpty()) {
            return null;
        }
        return String.format(MAPS_URL_TEMPLATE, placeId);
    }

    /**
     * Generate fallback Google search URL when Place ID is unavailable
     *
     * @param business The business
     * @return Search URL
     */
    public String generateSearchUrl(Business business) {
        if (business == null || business.getName() == null) {
            return null;
        }

        StringBuilder searchTerms = new StringBuilder();
        searchTerms.append(business.getName());

        if (business.getAddress() != null && !business.getAddress().trim().isEmpty()) {
            searchTerms.append(" ").append(business.getAddress());
        }

        searchTerms.append(" reviews");

        String encoded = URLEncoder.encode(searchTerms.toString(), StandardCharsets.UTF_8);
        return String.format(SEARCH_URL_TEMPLATE, encoded);
    }

    /**
     * Extract Place ID from g.page short URL
     *
     * Example: https://g.page/r/CZfH8POGJQGsEAI/review → CZfH8POGJQGsEAI
     *
     * @param shortUrl The g.page short URL
     * @return Extracted identifier (not a Place ID, but can be stored)
     */
    public String extractPlaceIdFromGPageUrl(String shortUrl) {
        if (shortUrl == null || shortUrl.trim().isEmpty()) {
            return null;
        }

        Matcher matcher = G_PAGE_PATTERN.matcher(shortUrl);
        if (matcher.find()) {
            return matcher.group(1);
        }

        log.warn("Invalid g.page URL format: {}", shortUrl);
        return null;
    }

    /**
     * Validate if a string is a valid Place ID format
     *
     * Google Place IDs are typically alphanumeric with underscores/hyphens
     *
     * @param placeId The Place ID to validate
     * @return true if valid format
     */
    public boolean isValidPlaceIdFormat(String placeId) {
        if (placeId == null || placeId.trim().isEmpty()) {
            return false;
        }

        // Place IDs are typically 20-100 characters, alphanumeric with underscores/hyphens
        return placeId.matches("^[A-Za-z0-9_-]{10,150}$");
    }

    /**
     * Apply auto-detected Place ID data to a business entity
     *
     * @param business The business to update
     * @param result The lookup result from Google Places API
     */
    public void applyPlacesDataToBusiness(Business business, PlacesLookupResult result) {
        if (business == null || result == null || !result.isSuccess()) {
            return;
        }

        business.setGooglePlaceId(result.getPlaceId());
        business.setGooglePlaceName(result.getPlaceName());
        business.setGooglePlaceFormattedAddress(result.getFormattedAddress());

        if (result.getTypes() != null && !result.getTypes().isEmpty()) {
            business.setGooglePlaceTypes(String.join(",", result.getTypes()));
        }

        business.setGoogleReviewUrl(generateReviewUrl(result.getPlaceId()));
        business.setGooglePlaceLastSynced(OffsetDateTime.now());
        business.setGooglePlaceAutoDetected(true);

        log.info("Applied Google Places data to business: {} (Place ID: {})",
                business.getName(), result.getPlaceId());
    }

    /**
     * Result object for Google Places lookup
     */
    public static class PlacesLookupResult {
        private boolean success;
        private String placeId;
        private String placeName;
        private String formattedAddress;
        private List<String> types;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getPlaceId() {
            return placeId;
        }

        public void setPlaceId(String placeId) {
            this.placeId = placeId;
        }

        public String getPlaceName() {
            return placeName;
        }

        public void setPlaceName(String placeName) {
            this.placeName = placeName;
        }

        public String getFormattedAddress() {
            return formattedAddress;
        }

        public void setFormattedAddress(String formattedAddress) {
            this.formattedAddress = formattedAddress;
        }

        public List<String> getTypes() {
            return types;
        }

        public void setTypes(List<String> types) {
            this.types = types;
        }
    }
}