package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // ----- User-scoped queries -----
    List<Customer> findByUserOrderByCreatedAtDesc(User user);
    /**
     * Find customer by ID and user
     */
    @Query("SELECT c FROM Customer c WHERE c.id = :id AND c.user = :user")
    Optional<Customer> findByIdAndUser(@Param("id") Long id, @Param("user") User user);
    List<Customer> findByUserAndBusinessOrderByCreatedAtDesc(User user, Business business);
    Optional<Customer> findByEmailAndUser(String email, User user);
    List<Customer> findByUserAndStatusOrderByCreatedAtDesc(User user, Customer.CustomerStatus status);

    @Query("SELECT c FROM Customer c JOIN c.tags t WHERE c.user = :user AND t = :tag ORDER BY c.createdAt DESC")
    List<Customer> findByUserAndTagsContaining(@Param("user") User user, @Param("tag") Customer.CustomerTag tag);

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(c.serviceType) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
            "ORDER BY c.createdAt DESC")
    List<Customer> searchCustomers(@Param("user") User user, @Param("searchTerm") String searchTerm);

    long countByUser(User user);
    long countByUserAndStatus(User user, Customer.CustomerStatus status);

    @Query("SELECT COUNT(c) FROM Customer c JOIN c.tags t WHERE c.user = :user AND t = :tag")
    long countByUserAndTag(@Param("user") User user, @Param("tag") Customer.CustomerTag tag);

    // Note: This uses YEAR/MONTH functions — OK on PostgreSQL via Hibernate,
    // but if you ever see dialect quirks, prefer a start/end range method.
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND " +
            "YEAR(c.createdAt) = YEAR(CURRENT_DATE) AND " +
            "MONTH(c.createdAt) = MONTH(CURRENT_DATE) " +
            "ORDER BY c.createdAt DESC")
    List<Customer> findCustomersThisMonth(@Param("user") User user);

    List<Customer> findByUserAndServiceDateBetweenOrderByServiceDateDesc(
            User user, LocalDate startDate, LocalDate endDate);

    // ----- Business-scoped queries -----
    List<Customer> findByBusiness(Business business);
    List<Customer> findByBusinessOrderByCreatedAtDesc(Business business);
    long countByBusiness(Business business);
    Optional<Customer> findByBusinessAndEmail(Business business, String email);
    Optional<Customer> findByBusinessAndPhone(Business business, String phone);
    boolean existsByBusinessAndEmail(Business business, String email);
    boolean existsByBusinessAndPhone(Business business, String phone);

    List<Customer> findByBusinessAndCreatedAtBetween(Business business, OffsetDateTime startDate, OffsetDateTime endDate);
    long countByBusinessAndCreatedAtBetween(Business business, OffsetDateTime startDate, OffsetDateTime endDate);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Customer> findByBusinessAndNameContainingIgnoreCase(@Param("business") Business business, @Param("searchTerm") String searchTerm);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Customer> findByBusinessAndEmailContainingIgnoreCase(@Param("business") Business business, @Param("searchTerm") String searchTerm);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.phone IS NOT NULL AND c.phone <> ''")
    List<Customer> findByBusinessWithPhone(@Param("business") Business business);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.business = :business AND c.phone IS NOT NULL AND c.phone <> ''")
    long countByBusinessWithPhone(@Param("business") Business business);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.email IS NOT NULL AND c.email <> ''")
    List<Customer> findByBusinessWithEmail(@Param("business") Business business);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.business = :business AND c.email IS NOT NULL AND c.email <> ''")
    long countByBusinessWithEmail(@Param("business") Business business);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.createdAt >= :sinceDate ORDER BY c.createdAt DESC")
    List<Customer> findRecentCustomers(@Param("business") Business business, @Param("sinceDate") OffsetDateTime sinceDate);

    @Query("SELECT " +
            "COUNT(c) as totalCustomers, " +
            "SUM(CASE WHEN c.email IS NOT NULL AND c.email <> '' THEN 1 ELSE 0 END) as withEmail, " +
            "SUM(CASE WHEN c.phone IS NOT NULL AND c.phone <> '' THEN 1 ELSE 0 END) as withPhone " +
            "FROM Customer c WHERE c.business = :business")
    Object[] getCustomerStatsByBusiness(@Param("business") Business business);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.phone LIKE CONCAT('%', :phoneDigits, '%')")
    List<Customer> findByBusinessAndPhoneContaining(@Param("business") Business business, @Param("phoneDigits") String phoneDigits);

    void deleteByBusinessAndCreatedAtBefore(Business business, OffsetDateTime cutoffDate);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND NOT EXISTS (SELECT r FROM ReviewRequest r WHERE r.customer = c)")
    List<Customer> findCustomersWithoutReviewRequests(@Param("business") Business business);

    @Query("SELECT DISTINCT c FROM Customer c JOIN ReviewRequest r ON r.customer = c WHERE c.business = :business AND r.status = 'SENT'")
    List<Customer> findCustomersWithActiveRequests(@Param("business") Business business);

    // ----- SMS-related -----
    Optional<Customer> findByPhone(String phone);

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone <> '' ORDER BY c.createdAt DESC")
    List<Customer> findSmsEligibleByUser(@Param("user") User user);

    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone <> '' ORDER BY c.createdAt DESC")
    List<Customer> findSmsEligibleByBusiness(@Param("business") Business business);

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsOptOut = TRUE ORDER BY c.smsOptOutTimestamp DESC")
    List<Customer> findSmsOptedOutByUser(@Param("user") User user);

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.phone IS NOT NULL " +
            "AND c.phone <> '' AND (c.smsOptIn IS NULL OR c.smsOptIn = FALSE) " +
            "AND (c.smsOptOut IS NULL OR c.smsOptOut = FALSE) ORDER BY c.createdAt DESC")
    List<Customer> findNeedingSmsConsentByUser(@Param("user") User user);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone <> ''")
    Long countSmsEligibleByUser(@Param("user") User user);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.phone IS NOT NULL AND c.phone <> ''")
    Long countWithPhoneByUser(@Param("user") User user);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE")
    Long countOptedInByUser(@Param("user") User user);

    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptOut = TRUE")
    Long countOptedOutByUser(@Param("user") User user);

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsLastSentTimestamp >= :since ORDER BY c.smsLastSentTimestamp DESC")
    List<Customer> findWithRecentSms(@Param("user") User user, @Param("since") OffsetDateTime since);

    // ---- FIX for the 'In' keyword collision in derived query names ----
    // Use explicit JPQL so Spring Data doesn't parse 'In' as the 'IN' operator.
    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.smsOptIn = :optIn")
    List<Customer> findByBusinessAndSmsOptIn(@Param("business") Business business, @Param("optIn") boolean optIn);

    // ----- Helper -----
    default Optional<Customer> findByPhoneAnyFormat(String incomingPhone) {
        if (incomingPhone == null || incomingPhone.trim().isEmpty()) {
            return Optional.empty();
        }
        String clean = incomingPhone.replaceAll("[^+\\d]", "");

        Optional<Customer> customer = findByPhone(clean);
        if (customer.isPresent()) return customer;

        if (!clean.startsWith("+")) {
            customer = findByPhone("+" + clean);
            if (customer.isPresent()) return customer;
        }

        if (clean.startsWith("+")) {
            customer = findByPhone(clean.substring(1));
            if (customer.isPresent()) return customer;
        }

        if (clean.length() == 10 && clean.matches("\\d{10}")) {
            customer = findByPhone("+1" + clean);
            if (customer.isPresent()) return customer;
        }

        return Optional.empty();
    }
    // ----- Eager fetch queries for ownership validation -----
    /**
     * Find customer by ID with business and user eagerly fetched.
     * Use this when you need to validate ownership or access business/user data.
     */
    @Query("SELECT c FROM Customer c " +
            "JOIN FETCH c.business b " +
            "JOIN FETCH b.user " +
            "WHERE c.id = :id")
    Optional<Customer> findByIdWithBusinessAndUser(@Param("id") Long id);

    /**
     * Find customer by ID and verify it belongs to the given user.
     * Returns empty if customer doesn't exist OR doesn't belong to user.
     */
    @Query("SELECT c FROM Customer c " +
            "JOIN FETCH c.business b " +
            "WHERE c.id = :id AND b.user = :user")
    Optional<Customer> findByIdAndBusinessUser(@Param("id") Long id, @Param("user") User user);

    /**
     * Find customer by email with business eagerly fetched.
     */
    @Query("SELECT c FROM Customer c " +
            "JOIN FETCH c.business b " +
            "WHERE c.email = :email")
    Optional<Customer> findByEmailWithBusiness(@Param("email") String email);

    /**
     * Find customer by email and user (for ownership validation).
     */
    @Query("SELECT c FROM Customer c " +
            "JOIN FETCH c.business b " +
            "WHERE c.email = :email AND b.user = :user")
    Optional<Customer> findByEmailAndBusinessUser(@Param("email") String email, @Param("user") User user);
}
