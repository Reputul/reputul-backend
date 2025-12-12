package com.reputul.backend.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reputul.backend.models.Business;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GooglePlacesService
 */
@ExtendWith(MockitoExtension.class)
class GooglePlacesServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private GooglePlacesService googlePlacesService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        googlePlacesService = new GooglePlacesService(restTemplate, objectMapper);

        // Set test API key
        ReflectionTestUtils.setField(googlePlacesService, "apiKey", "test-api-key");
    }

    @Test
    void testGenerateReviewUrl() {
        // Given
        String placeId = "ChIJN1t_tDeuEmsRUsoyG83frY4";

        // When
        String url = googlePlacesService.generateReviewUrl(placeId);

        // Then
        assertEquals("https://search.google.com/local/writereview?placeid=ChIJN1t_tDeuEmsRUsoyG83frY4", url);
    }

    @Test
    void testGenerateReviewUrl_NullPlaceId() {
        // When
        String url = googlePlacesService.generateReviewUrl(null);

        // Then
        assertNull(url);
    }

    @Test
    void testGenerateMapsUrl() {
        // Given
        String placeId = "ChIJN1t_tDeuEmsRUsoyG83frY4";

        // When
        String url = googlePlacesService.generateMapsUrl(placeId);

        // Then
        assertEquals("https://www.google.com/maps/place/?q=place_id:ChIJN1t_tDeuEmsRUsoyG83frY4", url);
    }

    @Test
    void testGenerateSearchUrl() {
        // Given
        Business business = Business.builder()
                .name("Bob's Roofing")
                .address("123 Main St, Denver, CO")
                .build();

        // When
        String url = googlePlacesService.generateSearchUrl(business);

        // Then
        assertTrue(url.contains("Bob%27s+Roofing"));
        assertTrue(url.contains("123+Main+St"));
        assertTrue(url.contains("reviews"));
    }

    @Test
    void testExtractPlaceIdFromGPageUrl_Valid() {
        // Given
        String shortUrl = "https://g.page/r/CZfH8POGJQGsEAI/review";

        // When
        String extracted = googlePlacesService.extractPlaceIdFromGPageUrl(shortUrl);

        // Then
        assertEquals("CZfH8POGJQGsEAI", extracted);
    }

    @Test
    void testExtractPlaceIdFromGPageUrl_WithoutReview() {
        // Given
        String shortUrl = "https://g.page/r/CZfH8POGJQGsEAI";

        // When
        String extracted = googlePlacesService.extractPlaceIdFromGPageUrl(shortUrl);

        // Then
        assertEquals("CZfH8POGJQGsEAI", extracted);
    }

    @Test
    void testExtractPlaceIdFromGPageUrl_Invalid() {
        // Given
        String invalidUrl = "https://example.com/invalid";

        // When
        String extracted = googlePlacesService.extractPlaceIdFromGPageUrl(invalidUrl);

        // Then
        assertNull(extracted);
    }

    @Test
    void testIsValidPlaceIdFormat_Valid() {
        // Given
        String placeId = "ChIJN1t_tDeuEmsRUsoyG83frY4";

        // When
        boolean isValid = googlePlacesService.isValidPlaceIdFormat(placeId);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testIsValidPlaceIdFormat_TooShort() {
        // Given
        String placeId = "short";

        // When
        boolean isValid = googlePlacesService.isValidPlaceIdFormat(placeId);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testIsValidPlaceIdFormat_InvalidCharacters() {
        // Given
        String placeId = "ChIJN1t_tDeuEmsRUsoyG83frY4!@#$";

        // When
        boolean isValid = googlePlacesService.isValidPlaceIdFormat(placeId);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testAutoDetectPlaceId_Success() throws Exception {
        // Given
        Business business = Business.builder()
                .name("Bob's Roofing")
                .address("123 Main St, Denver, CO")
                .build();

        String mockResponse = """
                {
                  "places": [
                    {
                      "id": "ChIJN1t_tDeuEmsRUsoyG83frY4",
                      "displayName": {
                        "text": "Bob's Roofing"
                      },
                      "formattedAddress": "123 Main St, Denver, CO 80014",
                      "types": ["roofing_contractor", "contractor"]
                    }
                  ]
                }
                """;

        when(restTemplate.postForEntity(anyString(), anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        // When
        GooglePlacesService.PlacesLookupResult result = googlePlacesService.autoDetectPlaceId(business);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", result.getPlaceId());
        assertEquals("Bob's Roofing", result.getPlaceName());
        assertEquals("123 Main St, Denver, CO 80014", result.getFormattedAddress());
    }

    @Test
    void testAutoDetectPlaceId_NoResults() throws Exception {
        // Given
        Business business = Business.builder()
                .name("Nonexistent Business")
                .address("999 Fake St")
                .build();

        String mockResponse = """
                {
                  "places": []
                }
                """;

        when(restTemplate.postForEntity(anyString(), anyString(), eq(String.class)))
                .thenReturn(new ResponseEntity<>(mockResponse, HttpStatus.OK));

        // When
        GooglePlacesService.PlacesLookupResult result = googlePlacesService.autoDetectPlaceId(business);

        // Then
        assertNull(result);
    }

    @Test
    void testAutoDetectPlaceId_MissingNameOrAddress() {
        // Given - business with no address
        Business business = Business.builder()
                .name("Bob's Roofing")
                .build();

        // When
        GooglePlacesService.PlacesLookupResult result = googlePlacesService.autoDetectPlaceId(business);

        // Then
        assertNull(result);
    }

    @Test
    void testApplyPlacesDataToBusiness() {
        // Given
        Business business = Business.builder()
                .name("Bob's Roofing")
                .build();

        GooglePlacesService.PlacesLookupResult result = new GooglePlacesService.PlacesLookupResult();
        result.setSuccess(true);
        result.setPlaceId("ChIJN1t_tDeuEmsRUsoyG83frY4");
        result.setPlaceName("Bob's Roofing LLC");
        result.setFormattedAddress("123 Main St, Denver, CO 80014");

        // When
        googlePlacesService.applyPlacesDataToBusiness(business, result);

        // Then
        assertEquals("ChIJN1t_tDeuEmsRUsoyG83frY4", business.getGooglePlaceId());
        assertEquals("Bob's Roofing LLC", business.getGooglePlaceName());
        assertEquals("123 Main St, Denver, CO 80014", business.getGooglePlaceFormattedAddress());
        assertNotNull(business.getGoogleReviewUrl());
        assertTrue(business.getGooglePlaceAutoDetected());
        assertNotNull(business.getGooglePlaceLastSynced());
    }
}