package com.reputul.backend.services;

import com.reputul.backend.dto.automation.AutomationWorkflowDto;
import com.reputul.backend.models.Organization;
import com.reputul.backend.models.User;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationServiceTest {

    @Mock
    private AutomationWorkflowRepository workflowRepository;

    @Mock
    private AutomationExecutionRepository executionRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private BusinessRepository businessRepository;

    @InjectMocks
    private AutomationService automationService;

    private User testUser;
    private Organization testOrg;
    private AutomationWorkflow testWorkflow;

    @BeforeEach
    void setUp() {
        testOrg = Organization.builder()
                .id(1L)
                .name("Test Organization")
                .build();

        testUser = User.builder()
                .id(1L)
                .name("Test User")
                .email("test@example.com")
                .organization(testOrg)
                .build();

        testWorkflow = AutomationWorkflow.builder()
                .id(1L)
                .name("Test Workflow")
                .organization(testOrg)
                .triggerType(AutomationWorkflow.TriggerType.CUSTOMER_CREATED)
                .deliveryMethod(AutomationWorkflow.DeliveryMethod.EMAIL)
                .isActive(true)
                .createdBy(testUser)
                .build();
    }

    @Test
    void createWorkflow_ShouldCreateWorkflowSuccessfully() {
        // Given
        AutomationService.CreateWorkflowRequest request = AutomationService.CreateWorkflowRequest.builder()
                .name("New Workflow")
                .description("New Description")
                .triggerType(AutomationWorkflow.TriggerType.CUSTOMER_CREATED)
                .actions(Map.of("send_email", Map.of("enabled", true)))
                .build();

        when(workflowRepository.save(any(AutomationWorkflow.class))).thenReturn(testWorkflow);

        // When
        AutomationWorkflow result = automationService.createWorkflow(testUser, request);

        // Then
        assertThat(result).isNotNull();
        verify(workflowRepository).save(any(AutomationWorkflow.class));
    }

    @Test
    void getWorkflows_ShouldReturnUserWorkflows() {
        // Given
        when(workflowRepository.findByOrganizationAndIsActiveTrueOrderByCreatedAtDesc(testOrg))
                .thenReturn(List.of(testWorkflow));

        // When
        List<AutomationWorkflowDto> result = automationService.getWorkflows(testUser, null);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(testWorkflow);
    }
}