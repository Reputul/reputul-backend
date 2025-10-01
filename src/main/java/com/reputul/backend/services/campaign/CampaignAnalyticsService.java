package com.reputul.backend.services.campaign;

import com.reputul.backend.dto.campaign.CampaignAnalyticsDto;
import com.reputul.backend.dto.campaign.CampaignPerformanceDto;
import com.reputul.backend.dto.campaign.MessageTypePerformanceDto;
import com.reputul.backend.enums.ExecutionStatus;
import com.reputul.backend.enums.StepStatus;
import com.reputul.backend.models.ReviewRequest;
import com.reputul.backend.models.campaign.CampaignExecution;
import com.reputul.backend.models.campaign.CampaignSequence;
import com.reputul.backend.models.campaign.CampaignStepExecution;
import com.reputul.backend.repositories.ReviewRequestRepository;
import com.reputul.backend.repositories.campaign.CampaignExecutionRepository;
import com.reputul.backend.repositories.campaign.CampaignSequenceRepository;
import com.reputul.backend.repositories.campaign.CampaignStepExecutionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class CampaignAnalyticsService {

    private final CampaignExecutionRepository executionRepository;
    private final CampaignStepExecutionRepository stepExecutionRepository;
    private final CampaignSequenceRepository sequenceRepository;
    private final ReviewRequestRepository reviewRequestRepository;

    /**
     * Get comprehensive campaign analytics for organization
     */
    public CampaignAnalyticsDto getCampaignAnalytics(Long orgId, LocalDateTime startDate, LocalDateTime endDate) {
        log.info("Generating campaign analytics for org {} from {} to {}", orgId, startDate, endDate);

        try {
            // Get all sequences for the organization
            List<CampaignSequence> orgSequences = sequenceRepository.findByOrgIdAndIsActiveTrue(orgId);
            List<Long> sequenceIds = orgSequences.stream().map(CampaignSequence::getId).collect(Collectors.toList());

            if (sequenceIds.isEmpty()) {
                return createEmptyAnalytics();
            }

            // Get execution counts by status
            Map<ExecutionStatus, Long> statusCounts = getExecutionStatusCounts(sequenceIds, startDate, endDate);

            Long totalExecutions = statusCounts.values().stream().mapToLong(Long::longValue).sum();
            Long completedExecutions = statusCounts.getOrDefault(ExecutionStatus.COMPLETED, 0L);

            Double completionRate = totalExecutions > 0 ?
                    (completedExecutions.doubleValue() / totalExecutions.doubleValue()) * 100 : 0.0;

            // Get sequence performance
            List<CampaignPerformanceDto> sequencePerformance = getSequencePerformanceList(orgSequences, startDate, endDate);

            return CampaignAnalyticsDto.builder()
                    .totalExecutions(totalExecutions)
                    .activeExecutions(statusCounts.getOrDefault(ExecutionStatus.ACTIVE, 0L))
                    .completedExecutions(completedExecutions)
                    .failedExecutions(statusCounts.getOrDefault(ExecutionStatus.FAILED, 0L))
                    .completionRate(completionRate)
                    .averageStepsCompleted(calculateAverageStepsCompleted(sequenceIds, startDate, endDate))
                    .sequencePerformance(sequencePerformance)
                    .build();

        } catch (Exception e) {
            log.error("Error generating campaign analytics for org {}: {}", orgId, e.getMessage(), e);
            return createEmptyAnalytics();
        }
    }

    /**
     * Get performance metrics for specific sequence
     */
    public CampaignPerformanceDto getSequencePerformance(Long sequenceId, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            CampaignSequence sequence = sequenceRepository.findById(sequenceId)
                    .orElseThrow(() -> new RuntimeException("Sequence not found: " + sequenceId));

            // Get executions for this sequence in date range
            List<CampaignExecution> executions = executionRepository.findBySequenceId(sequenceId).stream()
                    .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                    .collect(Collectors.toList());

            Long totalExecutions = (long) executions.size();
            Long completedExecutions = executions.stream()
                    .mapToLong(e -> ExecutionStatus.COMPLETED.equals(e.getStatus()) ? 1 : 0)
                    .sum();

            Double completionRate = totalExecutions > 0 ?
                    (completedExecutions.doubleValue() / totalExecutions.doubleValue()) * 100 : 0.0;

            // Calculate average completion time for completed executions
            Double avgCompletionTime = calculateAverageCompletionTime(executions);

            // Get message type performance
            MessageTypePerformanceDto smsPerformance = getSmsPerformance(sequenceId, startDate, endDate);
            MessageTypePerformanceDto emailPerformance = getEmailPerformance(sequenceId, startDate, endDate);

            return CampaignPerformanceDto.builder()
                    .sequenceId(sequenceId)
                    .sequenceName(sequence.getName())
                    .totalExecutions(totalExecutions)
                    .completedExecutions(completedExecutions)
                    .completionRate(completionRate)
                    .averageCompletionTime(avgCompletionTime)
                    .smsPerformance(smsPerformance)
                    .emailPerformance(emailPerformance)
                    .build();

        } catch (Exception e) {
            log.error("Error getting sequence performance for sequence {}: {}", sequenceId, e.getMessage(), e);
            return createEmptySequencePerformance(sequenceId);
        }
    }

    /**
     * Get campaign conversion rates (review submission rates)
     */
    public Double getCampaignConversionRate(Long sequenceId, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            // Get all executions for this sequence
            List<CampaignExecution> executions = executionRepository.findBySequenceId(sequenceId).stream()
                    .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                    .collect(Collectors.toList());

            if (executions.isEmpty()) {
                return 0.0;
            }

            // Count how many resulted in completed reviews
            long completedReviews = executions.stream()
                    .mapToLong(execution -> {
                        try {
                            ReviewRequest request = reviewRequestRepository.findById(execution.getReviewRequestId())
                                    .orElse(null);
                            return (request != null && ReviewRequest.RequestStatus.COMPLETED.equals(request.getStatus())) ? 1 : 0;
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .sum();

            return ((double) completedReviews / executions.size()) * 100;

        } catch (Exception e) {
            log.error("Error calculating conversion rate for sequence {}: {}", sequenceId, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get step execution statistics for a sequence
     */
    public Map<String, Object> getStepExecutionStats(Long sequenceId, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            // Get all step executions for this sequence
            List<CampaignStepExecution> stepExecutions = stepExecutionRepository
                    .findByExecutionIdOrderByScheduledAtAsc(null).stream() // This needs to be improved with proper query
                    .filter(se -> isInDateRange(se.getScheduledAt(), startDate, endDate))
                    .collect(Collectors.toList());

            Map<StepStatus, Long> statusCounts = stepExecutions.stream()
                    .collect(Collectors.groupingBy(
                            CampaignStepExecution::getStatus,
                            Collectors.counting()
                    ));

            return Map.of(
                    "totalSteps", stepExecutions.size(),
                    "pending", statusCounts.getOrDefault(StepStatus.PENDING, 0L),
                    "sent", statusCounts.getOrDefault(StepStatus.SENT, 0L),
                    "delivered", statusCounts.getOrDefault(StepStatus.DELIVERED, 0L),
                    "failed", statusCounts.getOrDefault(StepStatus.FAILED, 0L),
                    "skipped", statusCounts.getOrDefault(StepStatus.SKIPPED, 0L)
            );

        } catch (Exception e) {
            log.error("Error getting step execution stats for sequence {}: {}", sequenceId, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    // Private helper methods

    private Map<ExecutionStatus, Long> getExecutionStatusCounts(List<Long> sequenceIds, LocalDateTime startDate, LocalDateTime endDate) {
        List<CampaignExecution> allExecutions = sequenceIds.stream()
                .flatMap(sequenceId -> executionRepository.findBySequenceId(sequenceId).stream())
                .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                .collect(Collectors.toList());

        return allExecutions.stream()
                .collect(Collectors.groupingBy(
                        CampaignExecution::getStatus,
                        Collectors.counting()
                ));
    }

    private List<CampaignPerformanceDto> getSequencePerformanceList(List<CampaignSequence> sequences,
                                                                    LocalDateTime startDate, LocalDateTime endDate) {
        return sequences.stream()
                .map(sequence -> getSequencePerformance(sequence.getId(), startDate, endDate))
                .collect(Collectors.toList());
    }

    private Double calculateAverageStepsCompleted(List<Long> sequenceIds, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            List<CampaignExecution> completedExecutions = sequenceIds.stream()
                    .flatMap(sequenceId -> executionRepository.findBySequenceId(sequenceId).stream())
                    .filter(e -> ExecutionStatus.COMPLETED.equals(e.getStatus()))
                    .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                    .collect(Collectors.toList());

            if (completedExecutions.isEmpty()) {
                return 0.0;
            }

            // For now, return the average current step (simplified calculation)
            return completedExecutions.stream()
                    .mapToInt(CampaignExecution::getCurrentStep)
                    .average()
                    .orElse(0.0);

        } catch (Exception e) {
            log.error("Error calculating average steps completed: {}", e.getMessage());
            return 0.0;
        }
    }

    private Double calculateAverageCompletionTime(List<CampaignExecution> executions) {
        List<CampaignExecution> completedExecutions = executions.stream()
                .filter(e -> ExecutionStatus.COMPLETED.equals(e.getStatus()))
                .filter(e -> e.getStartedAt() != null && e.getCompletedAt() != null)
                .collect(Collectors.toList());

        if (completedExecutions.isEmpty()) {
            return 0.0;
        }

        return completedExecutions.stream()
                .mapToLong(e -> ChronoUnit.HOURS.between(e.getStartedAt(), e.getCompletedAt()))
                .average()
                .orElse(0.0);
    }

    private MessageTypePerformanceDto getSmsPerformance(Long sequenceId, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            // Get SMS step executions (simplified - in reality you'd need better queries)
            List<CampaignExecution> executions = executionRepository.findBySequenceId(sequenceId).stream()
                    .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                    .collect(Collectors.toList());

            // This is a simplified calculation - in practice you'd need more sophisticated queries
            long totalSms = executions.size(); // Approximate
            long deliveredSms = executions.stream()
                    .filter(e -> ExecutionStatus.COMPLETED.equals(e.getStatus()) || ExecutionStatus.ACTIVE.equals(e.getStatus()))
                    .collect(Collectors.toList())
                    .size();

            Double deliveryRate = totalSms > 0 ? (deliveredSms * 100.0 / totalSms) : 0.0;

            return MessageTypePerformanceDto.builder()
                    .totalSent(totalSms)
                    .delivered(deliveredSms)
                    .opened(0L) // SMS doesn't have open tracking
                    .clicked(0L) // Would need link click tracking
                    .deliveryRate(deliveryRate)
                    .openRate(0.0) // N/A for SMS
                    .clickRate(0.0) // Would need implementation
                    .build();

        } catch (Exception e) {
            log.error("Error calculating SMS performance: {}", e.getMessage());
            return createEmptyMessagePerformance();
        }
    }

    private MessageTypePerformanceDto getEmailPerformance(Long sequenceId, LocalDateTime startDate, LocalDateTime endDate) {
        try {
            // Get email-related review requests for this sequence
            List<CampaignExecution> executions = executionRepository.findBySequenceId(sequenceId).stream()
                    .filter(e -> isInDateRange(e.getStartedAt(), startDate, endDate))
                    .collect(Collectors.toList());

            // Get corresponding review requests
            List<ReviewRequest> emailRequests = executions.stream()
                    .map(e -> {
                        try {
                            return reviewRequestRepository.findById(e.getReviewRequestId()).orElse(null);
                        } catch (Exception ex) {
                            return null;
                        }
                    })
                    .filter(r -> r != null && ReviewRequest.DeliveryMethod.EMAIL.equals(r.getDeliveryMethod()))
                    .collect(Collectors.toList());

            long totalSent = emailRequests.size();
            long delivered = emailRequests.stream()
                    .filter(r -> r.getDeliveredAt() != null || ReviewRequest.RequestStatus.DELIVERED.equals(r.getStatus()))
                    .collect(Collectors.toList())
                    .size();
            long opened = emailRequests.stream()
                    .filter(r -> r.getOpenedAt() != null || ReviewRequest.RequestStatus.OPENED.equals(r.getStatus()))
                    .collect(Collectors.toList())
                    .size();
            long clicked = emailRequests.stream()
                    .filter(r -> r.getClickedAt() != null || ReviewRequest.RequestStatus.CLICKED.equals(r.getStatus()))
                    .collect(Collectors.toList())
                    .size();

            Double deliveryRate = totalSent > 0 ? (delivered * 100.0 / totalSent) : 0.0;
            Double openRate = delivered > 0 ? (opened * 100.0 / delivered) : 0.0;
            Double clickRate = opened > 0 ? (clicked * 100.0 / opened) : 0.0;

            return MessageTypePerformanceDto.builder()
                    .totalSent(totalSent)
                    .delivered(delivered)
                    .opened(opened)
                    .clicked(clicked)
                    .deliveryRate(deliveryRate)
                    .openRate(openRate)
                    .clickRate(clickRate)
                    .build();

        } catch (Exception e) {
            log.error("Error calculating email performance: {}", e.getMessage());
            return createEmptyMessagePerformance();
        }
    }

    private boolean isInDateRange(LocalDateTime dateTime, LocalDateTime startDate, LocalDateTime endDate) {
        if (dateTime == null) return false;
        return !dateTime.isBefore(startDate) && !dateTime.isAfter(endDate);
    }

    private CampaignAnalyticsDto createEmptyAnalytics() {
        return CampaignAnalyticsDto.builder()
                .totalExecutions(0L)
                .activeExecutions(0L)
                .completedExecutions(0L)
                .failedExecutions(0L)
                .completionRate(0.0)
                .averageStepsCompleted(0.0)
                .sequencePerformance(List.of())
                .build();
    }

    private CampaignPerformanceDto createEmptySequencePerformance(Long sequenceId) {
        return CampaignPerformanceDto.builder()
                .sequenceId(sequenceId)
                .sequenceName("Unknown")
                .totalExecutions(0L)
                .completedExecutions(0L)
                .completionRate(0.0)
                .averageCompletionTime(0.0)
                .smsPerformance(createEmptyMessagePerformance())
                .emailPerformance(createEmptyMessagePerformance())
                .build();
    }

    private MessageTypePerformanceDto createEmptyMessagePerformance() {
        return MessageTypePerformanceDto.builder()
                .totalSent(0L)
                .delivered(0L)
                .opened(0L)
                .clicked(0L)
                .deliveryRate(0.0)
                .openRate(0.0)
                .clickRate(0.0)
                .build();
    }
}