package com.reputul.backend.auth;

import com.reputul.backend.dto.ForgotPasswordRequestDto;
import com.reputul.backend.dto.LoginRequestDto;
import com.reputul.backend.dto.ResetPasswordRequestDto;
import com.reputul.backend.models.User;
import com.reputul.backend.payload.RegisterRequest;
import com.reputul.backend.repositories.UserRepository;
import com.reputul.backend.services.PasswordResetService;
import com.reputul.backend.services.EmailTemplateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    @PersistenceContext
    private EntityManager entityManager;

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private EmailTemplateService emailTemplateService;

    @Autowired
    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil,
                          UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequestDto request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        long expiration = request.isRememberMe() ?
                30L * 24 * 60 * 60 * 1000 : // 30 days
                24 * 60 * 60 * 1000; // 24 hours

        String token = jwtUtil.generateToken(auth.getName(), expiration);
        return ResponseEntity.ok(new AuthResponse(token));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email is already registered.");
        }

        User newUser = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        User savedUser = userRepository.save(newUser);

        // Create default templates for new user
        try {
            emailTemplateService.createDefaultTemplatesForUser(savedUser);
            log.info("✅ Created default email templates for new user: {}", savedUser.getEmail());
        } catch (Exception e) {
            log.error("❌ Failed to create default templates for user {}: {}", savedUser.getEmail(), e.getMessage());
            // Don't fail registration if template creation fails
        }

        return ResponseEntity.ok("User registered successfully!");
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<String> forgotPassword(@RequestBody ForgotPasswordRequestDto request) {
        try {
            boolean success = passwordResetService.initiatePasswordReset(request.getEmail());
            if (success) {
                return ResponseEntity.ok("If an account with that email exists, a password reset link has been sent. Please check your email and spam folder.");
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to process password reset request. Please try again.");
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to process password reset request. Please try again.");
        }
    }

    @PostMapping("/reset-password")
    public ResponseEntity<String> resetPassword(@RequestBody ResetPasswordRequestDto request) {
        try {
            passwordResetService.resetPassword(request.getToken(), request.getNewPassword());
            return ResponseEntity.ok("Password has been reset successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to reset password");
        }
    }

    // MAIN ADMIN METHOD: Update templates for all users
    @PostMapping("/admin/update-all-user-templates")
    public ResponseEntity<Map<String, Object>> updateAllUserTemplates() {
        log.info("🔄 Starting template update for ALL users");
        List<User> users = userRepository.findAll();
        log.info("Found {} users to process", users.size());

        Map<String, Object> result = new HashMap<>();
        int successCount = 0;
        int errorCount = 0;

        for (User user : users) {
            try {
                log.info("Updating templates for user: {}", user.getEmail());
                emailTemplateService.safeUpdateUserTemplates(user);
                log.info("✅ Successfully updated templates for user: {}", user.getEmail());
                successCount++;
            } catch (Exception e) {
                log.error("❌ Failed to update templates for user: {}", user.getEmail(), e);
                errorCount++;
            }
        }

        result.put("totalUsers", users.size());
        result.put("successCount", successCount);
        result.put("errorCount", errorCount);
        result.put("message", String.format("Template update completed: %d successful, %d errors", successCount, errorCount));

        log.info("✅ Template update process completed: {} successful, {} errors", successCount, errorCount);
        return ResponseEntity.ok(result);
    }

    // OPTIONAL: Get template statistics for debugging
    @GetMapping("/admin/template-stats")
    public ResponseEntity<Map<String, Object>> getTemplateStats() {
        try {
            List<User> users = userRepository.findAll();
            Map<String, Object> stats = new HashMap<>();

            for (User user : users) {
                var userStats = emailTemplateService.getUserTemplateStats(user);
                stats.put(user.getEmail(), userStats);
            }

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting template stats: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/complete-old-review-requests")
    @Transactional
    public ResponseEntity<Map<String, Object>> completeOldReviewRequests(
            @RequestParam(defaultValue = "30") int daysOld) {
        log.info("🧹 Completing review requests older than {} days", daysOld);

        try {
            String updateQuery = """
            UPDATE review_requests 
            SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
            WHERE created_at < CURRENT_DATE - INTERVAL '%d days'
            AND status IN ('PENDING', 'SENT', 'DELIVERED', 'OPENED', 'CLICKED')
            """.formatted(daysOld);

            int updatedCount = entityManager.createNativeQuery(updateQuery).executeUpdate();

            Map<String, Object> result = new HashMap<>();
            result.put("updatedCount", updatedCount);
            result.put("daysOld", daysOld);
            result.put("message", String.format("Completed %d old review requests", updatedCount));

            log.info("✅ Completed {} old review requests", updatedCount);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("❌ Error completing old review requests: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // CLEANUP: Complete review requests for a specific user
    @PostMapping("/admin/complete-user-review-requests/{userEmail}")
    @Transactional
    public ResponseEntity<Map<String, Object>> completeUserReviewRequests(
            @PathVariable String userEmail) {
        log.info("🧹 Completing all review requests for user: {}", userEmail);

        try {
            Optional<User> userOpt = userRepository.findByEmail(userEmail);
            if (!userOpt.isPresent()) {
                return ResponseEntity.notFound().build();
            }

            User user = userOpt.get();
            String updateQuery = """
            UPDATE review_requests 
            SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
            WHERE business_id IN (
                SELECT id FROM businesses WHERE user_id = :userId
            )
            AND status IN ('PENDING', 'SENT', 'DELIVERED', 'OPENED', 'CLICKED')
            """;

            int updatedCount = entityManager.createNativeQuery(updateQuery)
                    .setParameter("userId", user.getId())
                    .executeUpdate();

            Map<String, Object> result = new HashMap<>();
            result.put("userEmail", userEmail);
            result.put("updatedCount", updatedCount);
            result.put("message", String.format("Completed %d review requests for %s", updatedCount, userEmail));

            log.info("✅ Completed {} review requests for user: {}", updatedCount, userEmail);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("❌ Error completing review requests for user {}: {}", userEmail, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // DEBUG: Find which templates are still referenced by active review requests
    @GetMapping("/admin/debug-template-references")
    public ResponseEntity<Map<String, Object>> debugTemplateReferences() {
        try {
            String query = """
            
                    SELECT et.id, et.name, u.email, COUNT(rr.id) as active_references
            FROM email_templates et
            JOIN users u ON et.user_id = u.id
            JOIN review_requests rr ON et.id = rr.email_template_id 
            WHERE rr.status IN ('PENDING', 'SENT', 'DELIVERED', 'OPENED', 'CLICKED')
            GROUP BY et.id, et.name, u.email
            ORDER BY COUNT(rr.id) DESC
            """;

            List<Object[]> results = entityManager.createNativeQuery(query).getResultList();

            Map<String, Object> response = new HashMap<>();
            List<Map<String, Object>> references = new ArrayList<>();

            for (Object[] row : results) {
                Map<String, Object> ref = new HashMap<>();
                ref.put("templateId", row[0]);        // et.id
                ref.put("templateName", row[1]);      // et.name
                ref.put("userEmail", row[2]);         // u.email
                ref.put("activeReferences", row[3]);  // COUNT(rr.id) - FIXED: was row[4]
                references.add(ref);
            }

            response.put("templatesWithReferences", references);
            response.put("totalTemplatesWithReferences", references.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error debugging template references: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/complete-template-references/{templateId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> completeTemplateReferences(
            @PathVariable Long templateId) {
        log.info("🧹 Completing all review requests for template ID: {}", templateId);

        try {
            String updateQuery = """
        UPDATE review_requests 
        SET status = 'COMPLETED', updated_at = CURRENT_TIMESTAMP
        WHERE email_template_id = :templateId
        AND status != 'COMPLETED'
        """;

            int updatedCount = entityManager.createNativeQuery(updateQuery)
                    .setParameter("templateId", templateId)
                    .executeUpdate();

            Map<String, Object> result = new HashMap<>();
            result.put("templateId", templateId);
            result.put("updatedCount", updatedCount);
            result.put("message", String.format("Completed %d review requests for template %d", updatedCount, templateId));

            log.info("✅ Completed {} review requests for template: {}", updatedCount, templateId);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("❌ Error completing review requests for template {}: {}", templateId, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/admin/debug-template-43")
    public ResponseEntity<Map<String, Object>> debugTemplate43() {
        try {
            // Check if template 43 exists
            String templateQuery = "SELECT id, name, user_id FROM email_templates WHERE id = 43";
            List<Object[]> templateResult = entityManager.createNativeQuery(templateQuery).getResultList();

            // Check all review requests that reference it
            String reviewQuery = "SELECT id, status, email_template_id FROM review_requests WHERE email_template_id = 43";
            List<Object[]> reviewResult = entityManager.createNativeQuery(reviewQuery).getResultList();

            // Check the constraint details
            String constraintQuery = """
        SELECT 
            tc.constraint_name, 
            tc.table_name, 
            kcu.column_name, 
            ccu.table_name AS foreign_table_name,
            ccu.column_name AS foreign_column_name 
        FROM information_schema.table_constraints AS tc 
        JOIN information_schema.key_column_usage AS kcu
            ON tc.constraint_name = kcu.constraint_name
        JOIN information_schema.constraint_column_usage AS ccu
            ON ccu.constraint_name = tc.constraint_name
        WHERE tc.constraint_name = 'fkpk0kql5nb3p4aqq7bvq574mbl'
        """;
            List<Object[]> constraintResult = entityManager.createNativeQuery(constraintQuery).getResultList();

            Map<String, Object> response = new HashMap<>();
            response.put("templateExists", templateResult.size() > 0);
            response.put("templateData", templateResult);
            response.put("reviewRequestsReferencing", reviewResult);
            response.put("constraintInfo", constraintResult);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("❌ Error debugging template 43: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}