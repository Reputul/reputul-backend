package com.reputul.backend.repositories;

import com.reputul.backend.models.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    // Find recent import jobs for rate limiting
    @Query("SELECT COUNT(ij) FROM ImportJob ij WHERE ij.userId = :userId AND ij.createdAt > :since")
    long countByUserIdAndCreatedAtAfter(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    // Find failed jobs for cleanup
    List<ImportJob> findByStatusAndCreatedAtBefore(ImportJob.Status status, LocalDateTime before);
}