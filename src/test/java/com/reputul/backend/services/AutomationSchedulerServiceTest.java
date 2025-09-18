package com.reputul.backend.services;

import com.reputul.backend.models.Organization;
import com.reputul.backend.models.User;
import com.reputul.backend.models.Customer;
import com.reputul.backend.models.Business;
import com.reputul.backend.models.automation.AutomationWorkflow;
import com.reputul.backend.models.automation.AutomationExecution;
import com.reputul.backend.repositories.CustomerRepository;
import com.reputul.backend.repositories.OrganizationRepository;
import com.reputul.backend.repositories.automation.AutomationExecutionRepository;
import com.reputul.backend.repositories.automation.AutomationWorkflowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomationSchedulerServiceTest {

    @Mock
    private AutomationExecutionRepository executionRepository;

    @Mock
    private AutomationWorkflowRepository workflowRepository;

    @Mock
    private AutomationExecutorService automationExecutorService;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private AutomationSchedulerService schedulerService;

    private User testUser;
    private Organization testOrg;
    private Business testBusiness;
    private Customer testCustomer;
    private AutomationWorkflow testWorkflow;
    private AutomationExecution testExecution;

    @BeforeEach
    void setUp() {
        // Inject the real MeterRegistry
        schedulerService = new AutomationSchedulerService(
                executionRepository,
                workflowRepository,
                automationExecutorService,
                customerRepository,
                organizationRepository,
                meterRegistry
        );

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

        testBusiness = Business.builder()
                .id(1L)
                .name("Test Business")
                .user(testUser)
                .build();

        testCustomer = Customer.builder()
                .id(1L)
                .name("Test Customer")
                .email("customer@example.com")
                .serviceDate(LocalDate.now().minusDays(1))
                .business(testBusiness)
                .user(testUser)
                .build();

        testWorkflow = AutomationWorkflow.builder()
                .id(1L)
                .name("Test Workflow")
                .organization(testOrg)
                .triggerType(AutomationWorkflow.TriggerType.CUSTOMER_CREATED)
                .deliveryMethod(AutomationWorkflow.DeliveryMethod.EMAIL)
                .isActive(true)
                .build();

        testExecution = AutomationExecution.builder()
                .id(1L)
                .workflow(testWorkflow)
                .customer(testCustomer)
                .business(testBusiness)
                .status(AutomationExecution.ExecutionStatus.PENDING)
                .triggerEvent("TEST_EVENT")
                .scheduledFor(OffsetDateTime.now().minusMinutes(5))
                .build();
    }

    @Test
    void scheduleWorkflowExecution_ShouldCreateExecution() {
        // Given
        OffsetDateTime executeAt = OffsetDateTime.now().plusHours(1);
        Map<String, Object> triggerData = Map.of("test", "data");

        when(customerRepository.findById(1L)).thenReturn(Optional.of(testCustomer));
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        AutomationExecution result = schedulerService.scheduleWorkflowExecution(
                testWorkflow, 1L, "TEST_EVENT", executeAt, triggerData);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getWorkflow()).isEqualTo(testWorkflow);
        assertThat(result.getCustomer()).isEqualTo(testCustomer);
        verify(executionRepository).save(any(AutomationExecution.class));
    }

    @Test
    void scheduleWorkflowExecution_WithInvalidCustomer_ShouldThrowException() {
        // Given
        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> schedulerService.scheduleWorkflowExecution(
                testWorkflow, 999L, "TEST_EVENT", OffsetDateTime.now(), Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void scheduleWorkflowExecution_WithDifferentOrganization_ShouldThrowException() {
        // Given
        Organization differentOrg = Organization.builder().id(2L).name("Different Org").build();
        testCustomer.getUser().setOrganization(differentOrg);

        when(customerRepository.findById(1L)).thenReturn(Optional.of(testCustomer));

        // When & Then
        assertThatThrownBy(() -> schedulerService.scheduleWorkflowExecution(
                testWorkflow, 1L, "TEST_EVENT", OffsetDateTime.now(), Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("different organizations");
    }

    @Test
    void scheduleDelayedExecution_ShouldScheduleCorrectly() {
        // Given
        when(customerRepository.findById(1L)).thenReturn(Optional.of(testCustomer));
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        AutomationExecution result = schedulerService.scheduleDelayedExecution(
                testWorkflow, 1L, "TEST_EVENT", 1, 2, Map.of("test", "data"));

        // Then
        assertThat(result).isNotNull();
        verify(executionRepository).save(argThat(execution ->
                execution.getScheduledFor().isAfter(OffsetDateTime.now().plusHours(1))));
    }

    @Test
    void processPendingExecutions_ShouldProcessDueExecutions() {
        // Given
        when(organizationRepository.findAll()).thenReturn(List.of(testOrg));
        when(executionRepository.findDueExecutions(
                eq(AutomationExecution.ExecutionStatus.PENDING),
                any(OffsetDateTime.class),
                eq(testOrg.getId())))
                .thenReturn(List.of(testExecution));

        // When
        schedulerService.processPendingExecutions();

        // Then
        verify(executionRepository).findDueExecutions(
                eq(AutomationExecution.ExecutionStatus.PENDING),
                any(OffsetDateTime.class),
                eq(testOrg.getId()));
    }

    @Test
    void processExecution_ShouldUpdateExecutionStatus() {
        // Given
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));
        when(automationExecutorService.executeWorkflow(testExecution)).thenReturn(true);
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        schedulerService.processExecution(1L);

        // Then
        verify(automationExecutorService).executeWorkflow(testExecution);
        verify(executionRepository, times(2)).save(any(AutomationExecution.class));
    }

    @Test
    void processExecution_WithFailedExecution_ShouldMarkAsFailed() {
        // Given
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));
        when(automationExecutorService.executeWorkflow(testExecution)).thenReturn(false);
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        schedulerService.processExecution(1L);

        // Then - FIXED: Expect 2 saves (one for running status, one for failed status)
        verify(executionRepository, times(2)).save(any(AutomationExecution.class));
    }

    @Test
    void markExecutionCompleted_ShouldUpdateStatusAndTimestamp() {
        // Given
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        schedulerService.markExecutionCompleted(1L, "Test completion");

        // Then
        verify(executionRepository).save(argThat(execution ->
                execution.getStatus() == AutomationExecution.ExecutionStatus.COMPLETED &&
                        execution.getCompletedAt() != null));
    }

    @Test
    void markExecutionFailed_ShouldUpdateStatusAndErrorMessage() {
        // Given
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        schedulerService.markExecutionFailed(1L, "Test error");

        // Then
        verify(executionRepository).save(argThat(execution ->
                execution.getStatus() == AutomationExecution.ExecutionStatus.FAILED &&
                        execution.getErrorMessage().equals("Test error")));
    }

    @Test
    void cancelExecution_WithPendingExecution_ShouldCancel() {
        // Given
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));
        when(executionRepository.save(any(AutomationExecution.class))).thenReturn(testExecution);

        // When
        boolean result = schedulerService.cancelExecution(1L, "Test cancellation");

        // Then
        assertThat(result).isTrue();
        verify(executionRepository).save(argThat(execution ->
                execution.getStatus() == AutomationExecution.ExecutionStatus.CANCELLED));
    }

    @Test
    void cancelExecution_WithRunningExecution_ShouldNotCancel() {
        // Given
        testExecution.setStatus(AutomationExecution.ExecutionStatus.RUNNING);
        when(executionRepository.findById(1L)).thenReturn(Optional.of(testExecution));

        // When
        boolean result = schedulerService.cancelExecution(1L, "Test cancellation");

        // Then
        assertThat(result).isFalse();
        verify(executionRepository, never()).save(any(AutomationExecution.class));
    }
}