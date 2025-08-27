package com.reputul.backend.repositories;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByUserOrderByCreatedAtDesc(User user);
    List<Customer> findByBusinessOrderByCreatedAtDesc(Business business);
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

    @Query("SELECT c FROM Customer c WHERE c.user = :user AND " +
            "YEAR(c.createdAt) = YEAR(CURRENT_DATE) AND " +
            "MONTH(c.createdAt) = MONTH(CURRENT_DATE) " +
            "ORDER BY c.createdAt DESC")
    List<Customer> findCustomersThisMonth(@Param("user") User user);

    List<Customer> findByUserAndServiceDateBetweenOrderByServiceDateDesc(
            User user, LocalDate startDate, LocalDate endDate);

    // NEW SMS METHODS ONLY - No duplicates

    /**
     * Find customer by phone number (for inbound SMS processing)
     */
    Optional<Customer> findByPhone(String phone);

    /**
     * Find SMS-eligible customers by user
     */
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone != '' ORDER BY c.createdAt DESC")
    List<Customer> findSmsEligibleByUser(@Param("user") User user);

    /**
     * Find SMS-eligible customers by business
     */
    @Query("SELECT c FROM Customer c WHERE c.business = :business AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone != '' ORDER BY c.createdAt DESC")
    List<Customer> findSmsEligibleByBusiness(@Param("business") Business business);

    /**
     * Find customers who opted out of SMS by user
     */
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsOptOut = TRUE " +
            "ORDER BY c.smsOptOutTimestamp DESC")
    List<Customer> findSmsOptedOutByUser(@Param("user") User user);

    /**
     * Find customers needing SMS consent by user
     */
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.phone IS NOT NULL " +
            "AND c.phone != '' AND (c.smsOptIn IS NULL OR c.smsOptIn = FALSE) " +
            "AND (c.smsOptOut IS NULL OR c.smsOptOut = FALSE) ORDER BY c.createdAt DESC")
    List<Customer> findNeedingSmsConsentByUser(@Param("user") User user);

    /**
     * Count SMS-eligible customers by user
     */
    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE " +
            "AND (c.smsOptOut = FALSE OR c.smsOptOut IS NULL) AND c.phone IS NOT NULL " +
            "AND c.phone != ''")
    Long countSmsEligibleByUser(@Param("user") User user);

    /**
     * Count customers with phone numbers by user
     */
    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.phone IS NOT NULL AND c.phone != ''")
    Long countWithPhoneByUser(@Param("user") User user);

    /**
     * Count customers who opted in to SMS by user
     */
    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptIn = TRUE")
    Long countOptedInByUser(@Param("user") User user);

    /**
     * Count customers who opted out of SMS by user
     */
    @Query("SELECT COUNT(c) FROM Customer c WHERE c.user = :user AND c.smsOptOut = TRUE")
    Long countOptedOutByUser(@Param("user") User user);

    /**
     * Find customers with recent SMS activity
     */
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND c.smsLastSentTimestamp >= :since " +
            "ORDER BY c.smsLastSentTimestamp DESC")
    List<Customer> findWithRecentSms(@Param("user") User user, @Param("since") LocalDateTime since);

    /**
     * Helper method for phone lookup with format variations
     */
    default Optional<Customer> findByPhoneAnyFormat(String incomingPhone) {
        if (incomingPhone == null || incomingPhone.trim().isEmpty()) {
            return Optional.empty();
        }

        String clean = incomingPhone.replaceAll("[^+\\d]", "");

        // Try exact match first
        Optional<Customer> customer = findByPhone(clean);
        if (customer.isPresent()) return customer;

        // Try with + prefix
        if (!clean.startsWith("+")) {
            customer = findByPhone("+" + clean);
            if (customer.isPresent()) return customer;
        }

        // Try without + prefix
        if (clean.startsWith("+")) {
            customer = findByPhone(clean.substring(1));
            if (customer.isPresent()) return customer;
        }

        // Try with US country code for 10-digit numbers
        if (clean.length() == 10 && clean.matches("\\d{10}")) {
            customer = findByPhone("+1" + clean);
            if (customer.isPresent()) return customer;
        }

        return Optional.empty();
    }
}