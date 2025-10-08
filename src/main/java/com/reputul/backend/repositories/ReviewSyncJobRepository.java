package com.reputul.backend.repositories;

import com.reputul.backend.models.ReviewSyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewSyncJobRepository extends JpaRepository<ReviewSyncJob, Long> {

    /**
     * Find jobs by credential
     */
    List<ReviewSyncJob> findByCredentialIdOrderByCreatedAtDesc(Long credentialId);

    /**
     * Find jobs by business
     */
    List<ReviewSyncJob> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    /**
     * Find jobs by status
     */
    List<ReviewSyncJob> findByStatus(ReviewSyncJob.SyncStatus status);

    /**
     * Find latest job for credential
     */
    ReviewSyncJob findFirstByCredentialIdOrderByCreatedAtDesc(Long credentialId);
}