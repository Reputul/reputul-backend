package com.reputul.backend.repositories.automation;

import com.reputul.backend.models.automation.CustomerWorkflowState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerWorkflowStateRepository extends JpaRepository<CustomerWorkflowState, Long> {

    Optional<CustomerWorkflowState> findByCustomerIdAndWorkflowId(Long customerId, Long workflowId);

    List<CustomerWorkflowState> findByCustomerId(Long customerId);

    List<CustomerWorkflowState> findByWorkflowId(Long workflowId);

    List<CustomerWorkflowState> findByStatus(CustomerWorkflowState.StateStatus status);

    void deleteByWorkflowId(Long workflowId);
}