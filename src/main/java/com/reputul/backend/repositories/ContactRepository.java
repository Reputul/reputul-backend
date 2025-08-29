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

    // All contacts for a business (JPQL against the Contact entity)
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId")
    Page<Contact> findByBusinessId(@Param("businessId") Long businessId, Pageable pageable);

    // Text search by name/email/phone (JPQL)
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(COALESCE(c.email, '')) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "COALESCE(c.phone, '') LIKE CONCAT('%', :query, '%'))")
    Page<Contact> findByBusinessIdAndQuery(@Param("businessId") Long businessId,
                                           @Param("query") String query,
                                           Pageable pageable);

    // Tag filter only (native SQL; uses JSONB operator and can leverage GIN index)
    @Query(
            // language=PostgreSQL
            value = """
                SELECT *
                FROM public.contacts c
                WHERE c.business_id = :businessId
                  AND c.tags_json @> to_jsonb(ARRAY[lower(:tag)])
                """,
            // language=PostgreSQL
            countQuery = """
                SELECT COUNT(*)
                FROM public.contacts c
                WHERE c.business_id = :businessId
                  AND c.tags_json @> to_jsonb(ARRAY[lower(:tag)])
                """,
            nativeQuery = true
    )
    Page<Contact> findByBusinessIdAndTag(@Param("businessId") Long businessId,
                                         @Param("tag") String tag,
                                         Pageable pageable);

    // Text search + tag filter (native SQL)
    @Query(
            // language=PostgreSQL
            value = """
                SELECT *
                FROM public.contacts c
                WHERE c.business_id = :businessId
                  AND (
                        lower(c.name) LIKE lower(CONCAT('%', :query, '%'))
                        OR lower(coalesce(c.email, '')) LIKE lower(CONCAT('%', :query, '%'))
                        OR coalesce(c.phone, '') LIKE CONCAT('%', :query, '%')
                      )
                  AND c.tags_json @> to_jsonb(ARRAY[lower(:tag)])
                """,
            // language=PostgreSQL
            countQuery = """
                SELECT COUNT(*)
                FROM public.contacts c
                WHERE c.business_id = :businessId
                  AND (
                        lower(c.name) LIKE lower(CONCAT('%', :query, '%'))
                        OR lower(coalesce(c.email, '')) LIKE lower(CONCAT('%', :query, '%'))
                        OR coalesce(c.phone, '') LIKE CONCAT('%', :query, '%')
                      )
                  AND c.tags_json @> to_jsonb(ARRAY[lower(:tag)])
                """,
            nativeQuery = true
    )
    Page<Contact> findByBusinessIdAndQueryAndTag(@Param("businessId") Long businessId,
                                                 @Param("query") String query,
                                                 @Param("tag") String tag,
                                                 Pageable pageable);

    // Dedup helpers (derived queries)
    Optional<Contact> findByBusinessIdAndEmail(@Param("businessId") Long businessId, @Param("email") String email);
    Optional<Contact> findByBusinessIdAndPhone(@Param("businessId") Long businessId, @Param("phone") String phone);

    // Date range by name (JPQL)
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.name = :name AND " +
            "c.lastJobDate BETWEEN :startDate AND :endDate")
    List<Contact> findByBusinessIdAndNameAndLastJobDateBetween(@Param("businessId") Long businessId,
                                                               @Param("name") String name,
                                                               @Param("startDate") LocalDate startDate,
                                                               @Param("endDate") LocalDate endDate);

    // Count by business (derived)
    long countByBusinessId(Long businessId);

    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId ORDER BY c.createdAt DESC")
    List<Contact> findAllByBusinessIdForExport(@Param("businessId") Long businessId);

    // Export with tag (native SQL for JSONB)
    @Query(
            value = """
                SELECT *
                FROM public.contacts c
                WHERE c.business_id = :businessId
                  AND c.tags_json @> to_jsonb(ARRAY[lower(:tag)])
                ORDER BY c.created_at DESC
                """,
            nativeQuery = true
    )
    List<Contact> findAllByBusinessIdAndTagForExport(@Param("businessId") Long businessId, @Param("tag") String tag);

    // Batch lookups (JPQL)
    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.email IN :emails")
    List<Contact> findByBusinessIdAndEmailIn(@Param("businessId") Long businessId, @Param("emails") List<String> emails);

    @Query("SELECT c FROM Contact c WHERE c.businessId = :businessId AND c.phone IN :phones")
    List<Contact> findByBusinessIdAndPhoneIn(@Param("businessId") Long businessId, @Param("phones") List<String> phones);
}
