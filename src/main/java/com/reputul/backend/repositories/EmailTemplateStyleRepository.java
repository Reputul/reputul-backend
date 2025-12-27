package com.reputul.backend.repositories;

import com.reputul.backend.models.EmailTemplateStyle;
import com.reputul.backend.models.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailTemplateStyleRepository extends JpaRepository<EmailTemplateStyle, Long> {

    Optional<EmailTemplateStyle> findByOrganization(Organization organization);

    Optional<EmailTemplateStyle> findByOrganizationId(Long organizationId);

    boolean existsByOrganization(Organization organization);

    void deleteByOrganization(Organization organization);
}