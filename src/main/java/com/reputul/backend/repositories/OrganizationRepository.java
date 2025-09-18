package com.reputul.backend.repositories;

import com.reputul.backend.models.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /**
     * Find organization by name
     */
    Optional<Organization> findByName(String name);

    /**
     * Find organizations with active users
     */
    @Query("SELECT DISTINCT o FROM Organization o JOIN User u ON u.organization = o")
    List<Organization> findOrganizationsWithUsers();

    /**
     * Count users in organization
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.organization.id = :orgId")
    long countUsersByOrganizationId(Long orgId);
}