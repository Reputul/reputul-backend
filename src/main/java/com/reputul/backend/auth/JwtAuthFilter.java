package com.reputul.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
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
            "/api/billing/webhook/",  // Add webhook paths
            "/api/webhooks/"           // Add webhook paths
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

        // Skip JWT processing for public paths
        if (isPublicPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // If no Authorization header, continue without authentication
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // Extract JWT token
            jwt = authHeader.substring(7);
            userEmail = jwtUtil.extractUsername(jwt);

            // Authenticate user if not already authenticated
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userAuthService.loadUserByUsername(userEmail);

                if (jwtUtil.validateToken(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Log the error but don't block the request - let Spring Security handle it
            System.err.println("JWT Authentication error: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Check if the request path is a public endpoint that should skip JWT authentication
     */
    private boolean isPublicPath(String requestPath) {
        return PUBLIC_PATHS.stream().anyMatch(requestPath::startsWith);
    }
}