package com.reputul.backend.platform.repository;

import com.reputul.backend.platform.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    /**
     * Find all API keys for an organization
     */
    List<ApiKey> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /**
     * Find active API keys for an organization
     */
    @Query("SELECT a FROM ApiKey a WHERE a.organizationId = :organizationId AND a.revokedAt IS NULL ORDER BY a.createdAt DESC")
    List<ApiKey> findActiveByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * Find API key by hash (for authentication)
     */
    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    /**
     * Check if name already exists for organization
     */
    boolean existsByOrganizationIdAndName(Long organizationId, String name);
}