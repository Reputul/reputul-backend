package com.reputul.backend.services;

import com.reputul.backend.models.PasswordResetToken;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.PasswordResetTokenRepository;
import com.reputul.backend.repositories.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@Slf4j
public class PasswordResetService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    public boolean initiatePasswordReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            // Don't reveal if email exists or not for security
            log.info("Password reset requested for non-existent email: {}", email);
            return true; // Return true instead of just returning void
        }

        User user = userOpt.get();

        // Delete any existing tokens for this user
        tokenRepository.deleteByUser(user);

        // Generate new token
        String token = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);

        PasswordResetToken resetToken = new PasswordResetToken(user, token, expiresAt);
        tokenRepository.save(resetToken);

        // Send email using your existing EmailService
        boolean emailSent = emailService.sendPasswordResetEmail(user.getEmail(), token);

        if (emailSent) {
            log.info("✅ Password reset email sent successfully to: {}", user.getEmail());
            return true;
        } else {
            log.error("❌ Failed to send password reset email to: {}", user.getEmail());
            return false;
        }
    }

    public void resetPassword(String token, String newPassword) {
        Optional<PasswordResetToken> tokenOpt = tokenRepository.findByToken(token);

        if (tokenOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid reset token");
        }

        PasswordResetToken resetToken = tokenOpt.get();

        if (resetToken.getUsed()) {
            throw new IllegalArgumentException("Reset token has already been used");
        }

        if (resetToken.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Reset token has expired");
        }

        // Update password
        User user = resetToken.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Mark token as used
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        // Clean up expired tokens
        tokenRepository.deleteExpiredTokens(OffsetDateTime.now(ZoneOffset.UTC));

        log.info("✅ Password successfully reset for user: {}", user.getEmail());
    }
}