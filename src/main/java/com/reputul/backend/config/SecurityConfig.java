package com.reputul.backend.config;

import com.reputul.backend.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Value;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Value("${cors.allowed.origins}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authz -> authz
                        // Allow all OPTIONS requests (CORS preflight)
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Health check endpoints
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health/**").permitAll()
                        .requestMatchers("/api/test/**").permitAll()

                        // OpenAPI/Swagger endpoints
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // Auth endpoints (unversioned)
                        .requestMatchers("/api/auth/**").permitAll()

                        // Public review endpoints (versioned and unversioned)
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/reviews/business/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/reviews/public/**").permitAll()

                        // Public business endpoints (for public pages)
//                        .requestMatchers(HttpMethod.GET, "/api/v1/businesses/*").permitAll()

                        // File serving
                        .requestMatchers("/api/v1/files/**").permitAll()

                        // Webhooks (no auth needed)
                        .requestMatchers("/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/api/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/sms/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/email/webhook/**").permitAll()

                        // Stripe webhook endpoints (MUST be public)
                        .requestMatchers(HttpMethod.POST, "/api/billing/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/billing/webhook/**").permitAll()

                        // Waitlist endpoints (for landing page)
                        .requestMatchers("/api/v1/waitlist/**").permitAll()
                        .requestMatchers("/api/waitlist/**").permitAll()

                        // SMS endpoints (for Twilio compliance)
                        .requestMatchers("/api/sms-samples/**").permitAll()
                        .requestMatchers("/api/sms-signup/**").permitAll()

                        // Customer direct endpoints (no auth for review submission)
                        .requestMatchers("/api/customers/**").permitAll()
                        .requestMatchers("/api/review-requests/send-direct").permitAll()

                        // Customer feedback pages (public)
                        .requestMatchers("/api/v1/customers/*/feedback-info").permitAll()

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);

        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"
        ));

        configuration.setAllowedHeaders(Arrays.asList("*"));

        configuration.setExposedHeaders(Arrays.asList(
                "Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"
        ));

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}