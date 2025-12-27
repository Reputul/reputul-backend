package com.reputul.backend.repositories;

import com.reputul.backend.models.EmailTemplate;
import com.reputul.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {

    // Find all templates by user
    List<EmailTemplate> findByUserOrderByCreatedAtDesc(User user);

    // Find active templates by user
    List<EmailTemplate> findByUserAndIsActiveTrueOrderByCreatedAtDesc(User user);

    // Find templates by user and type
    List<EmailTemplate> findByUserAndTypeOrderByCreatedAtDesc(User user, EmailTemplate.TemplateType type);

    // Find active templates by user and type
    List<EmailTemplate> findByUserAndTypeAndIsActiveTrueOrderByCreatedAtDesc(User user, EmailTemplate.TemplateType type);

    // Find default template by user and type
    Optional<EmailTemplate> findByUserAndTypeAndIsDefaultTrue(User user, EmailTemplate.TemplateType type);

    // Find template by ID and user (for security)
    Optional<EmailTemplate> findByIdAndUser(Long id, User user);

    // Check if user has any templates
    boolean existsByUser(User user);

    // Count templates by user
    long countByUser(User user);

    // Count active templates by user
    long countByUserAndIsActiveTrue(User user);

    // Search templates by name or subject
    @Query("SELECT t FROM EmailTemplate t WHERE t.user = :user AND " +
            "(LOWER(t.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(t.subject) LIKE LOWER(CONCAT('%', :searchTerm, '%'))) " +
            "ORDER BY t.createdAt DESC")
    List<EmailTemplate> searchTemplates(@Param("user") User user, @Param("searchTerm") String searchTerm);

    // Find templates by type across all users (for system defaults)
    List<EmailTemplate> findByType(EmailTemplate.TemplateType type);

    // Find default template by organization ID and type (for campaign integration)
    @Query("SELECT t FROM EmailTemplate t WHERE t.user.organization.id = :orgId AND t.type = :type AND t.isDefault = true")
    List<EmailTemplate> findByOrgIdAndTypeAndIsDefaultTrue(@Param("orgId") Long orgId, @Param("type") EmailTemplate.TemplateType type);
}