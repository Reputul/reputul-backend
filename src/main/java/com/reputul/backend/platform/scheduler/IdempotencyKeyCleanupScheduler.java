package com.reputul.backend.platform.scheduler;

import com.reputul.backend.platform.repository.IdempotencyKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Scheduled job to clean up expired idempotency keys
 * Runs daily at 2 AM to keep the table size manageable
 */
@Component
@Slf4j
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyKeyCleanupScheduler(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Clean up expired idempotency keys
     * Runs daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupExpiredKeys() {
        log.info("Starting idempotency key cleanup job...");

        try {
            int deletedCount = idempotencyKeyRepository.deleteExpired(LocalDateTime.now());
            log.info("Idempotency key cleanup completed. Deleted {} expired keys.", deletedCount);
        } catch (Exception e) {
            log.error("Error during idempotency key cleanup: {}", e.getMessage(), e);
        }
    }
}