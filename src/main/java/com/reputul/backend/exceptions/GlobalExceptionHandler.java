package com.reputul.backend.exceptions;

import com.reputul.backend.integrations.PlatformIntegrationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for REST controllers
 *
 * UPDATED: Handles TokenExpiredException with specific response format
 * for frontend to trigger reconnection flow
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle TokenExpiredException - return 401 with reconnection details
     */
    @ExceptionHandler(TokenExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleTokenExpired(TokenExpiredException ex) {
        log.warn("Token expired for platform {}, credential {}: {}",
                ex.getPlatformType(), ex.getCredentialId(), ex.getMessage());

        Map<String, Object> response = new HashMap<>();
        response.put("error", "TOKEN_EXPIRED");
        response.put("message", ex.getMessage());
        response.put("platformType", ex.getPlatformType());
        response.put("credentialId", ex.getCredentialId());
        response.put("canReconnect", ex.canReconnect());
        response.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(response);
    }

    /**
     * Handle generic PlatformIntegrationException
     */
    @ExceptionHandler(PlatformIntegrationException.class)
    public ResponseEntity<Map<String, Object>> handlePlatformIntegrationException(
            PlatformIntegrationException ex) {
        log.error("Platform integration error: {}", ex.getMessage(), ex);

        Map<String, Object> response = new HashMap<>();
        response.put("error", "PLATFORM_ERROR");
        response.put("message", ex.getMessage());
        response.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    /**
     * Handle generic exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        Map<String, Object> response = new HashMap<>();
        response.put("error", "INTERNAL_ERROR");
        response.put("message", "An unexpected error occurred");
        response.put("timestamp", OffsetDateTime.now(ZoneOffset.UTC));

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}