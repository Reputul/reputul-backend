package com.reputul.backend.repositories;

import com.reputul.backend.models.Contact;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    // Find all contacts for a business with optional filtering
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId")
    Page<Contact> findByBusinessId(@Param("businessId") Long businessId, Pageable pageable);

    // Search contacts by name, email, or phone within business
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(COALESCE(c.email, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "COALESCE(c.phone, '') LIKE CONCAT('%', :query, '%'))")
    Page<Contact> findByBusinessIdAndQuery(@Param("businessId") Long businessId,
                                           @Param("query") String query,
                                           Pageable pageable);

    // Find contacts with specific tag (simplified approach)
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND " +
            "c.tagsJson IS NOT NULL AND c.tagsJson LIKE CONCAT('%\"', LOWER(:tag), '\"%')")
    Page<Contact> findByBusinessIdAndTag(@Param("businessId") Long businessId,
                                         @Param("tag") String tag,
                                         Pageable pageable);

    // Search with both query and tag filter
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(COALESCE(c.email, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "COALESCE(c.phone, '') LIKE CONCAT('%', :query, '%')) AND " +
            "c.tagsJson IS NOT NULL AND c.tagsJson LIKE CONCAT('%\"', LOWER(:tag), '\"%')")
    Page<Contact> findByBusinessIdAndQueryAndTag(@Param("businessId") Long businessId,
                                                 @Param("query") String query,
                                                 @Param("tag") String tag,
                                                 Pageable pageable);

    // Find by unique constraints for deduplication
    Optional<Contact> findByBusinessIdAndEmail(@Param("businessId") Long businessId, @Param("email") String email);

    Optional<Contact> findByBusinessIdAndPhone(@Param("businessId") Long businessId, @Param("phone") String phone);

    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.name = :name AND " +
            "c.lastJobDate BETWEEN :startDate AND :endDate")
    List<Contact> findByBusinessIdAndNameAndLastJobDateBetween(@Param("businessId") Long businessId,
                                                               @Param("name") String name,
                                                               @Param("startDate") LocalDate startDate,
                                                               @Param("endDate") LocalDate endDate);

    // Count contacts for business
    long countByBusinessId(Long businessId);

    // Export query - get all contacts for a business with optional tag filter
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId ORDER BY c.createdAt DESC")
    List<Contact> findAllByBusinessIdForExport(@Param("businessId") Long businessId);

    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND " +
            "c.tagsJson IS NOT NULL AND c.tagsJson LIKE CONCAT('%\"', LOWER(:tag), '\"%') " +
            "ORDER BY c.createdAt DESC")
    List<Contact> findAllByBusinessIdAndTagForExport(@Param("businessId") Long businessId, @Param("tag") String tag);

    // Batch operations for import
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.email IN :emails")
    List<Contact> findByBusinessIdAndEmailIn(@Param("businessId") Long businessId, @Param("emails") List<String> emails);

    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.phone IN :phones")
    List<Contact> findByBusinessIdAndPhoneIn(@Param("businessId") Long businessId, @Param("phones") List<String> phones);
}