package com.reputul.backend.repositories;

import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    // Find all customers by user
    List<Customer> findByUserOrderByCreatedAtDesc(User user);

    // Find customers by business
    List<Customer> findByBusinessOrderByCreatedAtDesc(Business business);

    // Find customers by user and business
    List<Customer> findByUserAndBusinessOrderByCreatedAtDesc(User user, Business business);

    // Find customer by email and user (for duplicate checking)
    Optional<Customer> findByEmailAndUser(String email, User user);

    // Find customers by status
    List<Customer> findByUserAndStatusOrderByCreatedAtDesc(User user, Customer.CustomerStatus status);

    // Find customers by tag
    @Query("SELECT c FROM Customer c JOIN c.tags t WHERE c.user = :user AND t = :tag ORDER BY c.createdAt DESC")
    List<Customer> findByUserAndTagsContaining(@Param("user") User user, @Param("tag") Customer.CustomerTag tag);

    // Search customers by name, email, or service type
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND " +
            "(LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(c.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(c.serviceType) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
            "ORDER BY c.createdAt DESC")
    List<Customer> searchCustomers(@Param("user") User user, @Param("searchTerm") String searchTerm);

    // Count customers by user
    long countByUser(User user);

    // Count customers by user and status
    long countByUserAndStatus(User user, Customer.CustomerStatus status);

    // Count customers by user and tag
    @Query("SELECT COUNT(c) FROM Customer c JOIN c.tags t WHERE c.user = :user AND t = :tag")
    long countByUserAndTag(@Param("user") User user, @Param("tag") Customer.CustomerTag tag);

    // Find customers created this month
    @Query("SELECT c FROM Customer c WHERE c.user = :user AND " +
            "YEAR(c.createdAt) = YEAR(CURRENT_DATE) AND " +
            "MONTH(c.createdAt) = MONTH(CURRENT_DATE) " +
            "ORDER BY c.createdAt DESC")
    List<Customer> findCustomersThisMonth(@Param("user") User user);

    // Find customers by service date range
    List<Customer> findByUserAndServiceDateBetweenOrderByServiceDateDesc(
            User user, LocalDate startDate, LocalDate endDate);
}