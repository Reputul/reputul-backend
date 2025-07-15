package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    List<Business> findByOwnerId(Long ownerId);
}
