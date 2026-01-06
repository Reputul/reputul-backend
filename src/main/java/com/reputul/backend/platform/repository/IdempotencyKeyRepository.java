package com.reputul.backend.platform.repository;

import com.reputul.backend.platform.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Find idempotency key that hasn't expired
     */
    Optional<IdempotencyKey> findByKeyAndExpiresAtAfter(String key, LocalDateTime now);

    /**
     * Delete expired idempotency keys (cleanup job)
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey i WHERE i.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}