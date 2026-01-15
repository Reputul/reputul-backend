package com.reputul.backend.repositories;

import com.reputul.backend.models.ChannelCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelCredentialRepository extends JpaRepository<ChannelCredential, Long> {

    /**
     * Find all credentials for a business
     */
    List<ChannelCredential> findByBusinessId(Long businessId);

    /**
     * Find credentials by business and status
     */
    List<ChannelCredential> findByBusinessIdAndStatus(Long businessId, ChannelCredential.CredentialStatus status);

    /**
     * Find credentials by business and platform type
     */
    Optional<ChannelCredential> findByBusinessIdAndPlatformType(Long businessId, ChannelCredential.PlatformType platformType);

    /**
     * Find credentials by organization
     */
    List<ChannelCredential> findByOrganizationId(Long organizationId);

    /**
     * ADDED: Find all credentials by status (for scheduled jobs)
     * More efficient than findAll() + filter
     */
    List<ChannelCredential> findByStatus(ChannelCredential.CredentialStatus status);

    /**
     * Find active credentials due for sync
     */
    @Query("SELECT c FROM ChannelCredential c WHERE c.status = :status " +
            "AND c.nextSyncScheduled IS NOT NULL " +
            "AND c.nextSyncScheduled <= :now")
    List<ChannelCredential> findDueForSync(
            @Param("now") OffsetDateTime now,
            @Param("status") ChannelCredential.CredentialStatus status);

    /**
     * Find credential by metadata (used for OAuth state validation)
     */
    @Query("SELECT c FROM ChannelCredential c WHERE c.metadataJson LIKE CONCAT('%', :searchString, '%')")
    Optional<ChannelCredential> findByMetadataContaining(@Param("searchString") String searchString);

    /**
     * Check if platform is already connected for business
     */
    boolean existsByBusinessIdAndPlatformType(Long businessId, ChannelCredential.PlatformType platformType);

    /**
     * ADDED: Find credentials with tokens expiring soon (for token refresh job)
     * More efficient than findAll() + filter
     */
    @Query("SELECT c FROM ChannelCredential c WHERE c.status = :status " +
            "AND c.tokenExpiresAt IS NOT NULL " +
            "AND c.tokenExpiresAt < :expiryThreshold")
    List<ChannelCredential> findByStatusAndTokenExpiresAtBefore(
            @Param("status") ChannelCredential.CredentialStatus status,
            @Param("expiryThreshold") OffsetDateTime expiryThreshold);

    /**
     * Find credentials by status and connection method
     */
    List<ChannelCredential> findByStatusAndConnectionMethod(
            ChannelCredential.CredentialStatus status,
            String connectionMethod
    );

    /**
     * Find credentials by platform type and metadata containing a specific value
     * Native PostgreSQL query for JSONB column search
     */
    @Query(value = "SELECT * FROM channel_credentials " +
            "WHERE platform_type = CAST(:platformType AS text) " +
            "AND metadata::text LIKE CONCAT('%', :metadataValue, '%')",
            nativeQuery = true)
    List<ChannelCredential> findByPlatformTypeAndMetadataContaining(
            @Param("platformType") String platformType,
            @Param("metadataValue") String metadataValue
    );

    /**
     * Find all credentials by platform type
     * Used for Facebook data deletion to get all Facebook credentials, then filter by metadata
     */
    List<ChannelCredential> findByPlatformType(ChannelCredential.PlatformType platformType);



}