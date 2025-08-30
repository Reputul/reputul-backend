package com.reputul.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JWT Authentication Filter with proper webhook endpoint exclusions
 *
 * CRITICAL: Webhook endpoints must be excluded from JWT authentication
 * to allow Stripe and other external services to call them
 */
@Component
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserAuthService userAuthService;

    // Define public endpoints that should skip JWT authentication
    private static final List<String> PUBLIC_PATHS = Arrays.asList(
            "/api/health",
            "/api/auth/",
            "/api/public/",
            "/api/reviews/business/",
            "/api/reviews/public/",
            "/api/customers/",
            "/api/waitlist/",
            "/api/review-requests/send-direct",
            // CRITICAL: Webhook paths must be public for external services
            "/api/billing/webhook/",  // Stripe webhooks
            "/api/webhooks/",         // Generic webhooks
            "/api/sms/webhook/",      // SMS delivery webhooks (Twilio)
            "/api/email/webhook/"     // Email webhooks (SendGrid)
    );

    // Additional paths that need to be completely public (OPTIONS for CORS)
    private static final List<String> CORS_PATHS = Arrays.asList(
            "/api/billing/webhook/stripe",
            "/api/billing/webhook/health"
    );

    public JwtAuthFilter(JwtUtil jwtUtil, UserAuthService userAuthService) {
        this.jwtUtil = jwtUtil;
        this.userAuthService = userAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        String method = request.getMethod();

        // Log webhook requests for debugging
        if (requestURI.contains("webhook")) {
            log.debug("Processing {} request to webhook endpoint: {}", method, requestURI);
        }

        // Skip JWT processing for public paths and CORS preflight requests
        if (isPublicPath(requestURI) || "OPTIONS".equals(method)) {
            log.debug("Skipping JWT authentication for public path: {} {}", method, requestURI);
            filterChain.doFilter(request, response);
            return;
        }

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // If no Authorization header, continue without authentication
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("No Bearer token found for: {} {}", method, requestURI);
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Extract JWT token
            jwt = authHeader.substring(7);
            userEmail = jwtUtil.extractUsername(jwt);

            // If we have a valid token and no authentication is set
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Load user details
                UserDetails userDetails = userAuthService.loadUserByUsername(userEmail);

                // Validate token
                if (jwtUtil.validateToken(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities());

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Successfully authenticated user: {} for: {} {}", userEmail, method, requestURI);
                } else {
                    log.warn("Invalid JWT token for user: {} on: {} {}", userEmail, method, requestURI);
                }
            }

        } catch (Exception e) {
            log.error("Error processing JWT token for: {} {}: {}", method, requestURI, e.getMessage());
            // Don't throw exception, let request proceed without authentication
            // The endpoint will handle unauthorized access appropriately
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if the request path is public and should skip JWT authentication
     */
    private boolean isPublicPath(String requestURI) {
        // Exact matches for CORS paths
        if (CORS_PATHS.contains(requestURI)) {
            return true;
        }

        // Prefix matches for public paths
        for (String publicPath : PUBLIC_PATHS) {
            if (requestURI.startsWith(publicPath)) {
                log.debug("Matched public path: {} for URI: {}", publicPath, requestURI);
                return true;
            }
        }

        return false;
    }

    /**
     * Additional check for webhook-specific paths
     * Webhooks should NEVER require authentication
     */
    private boolean isWebhookPath(String requestURI) {
        return requestURI.contains("/webhook/") || requestURI.endsWith("/webhook");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String requestURI = request.getRequestURI();

        // Never filter webhook endpoints - they must always be public
        if (isWebhookPath(requestURI)) {
            log.debug("Skipping filter for webhook endpoint: {}", requestURI);
            return true;
        }

        // Skip filtering for OPTIONS requests (CORS preflight)
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }

        return false;
    }
}