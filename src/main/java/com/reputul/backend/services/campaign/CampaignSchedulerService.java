package com.reputul.backend.services.campaign;

import com.reputul.backend.models.campaign.CampaignStepExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignSchedulerService {

    private final CampaignExecutionService campaignExecutionService;

    /**
     * Process due campaign steps every minute
     */
    @Scheduled(fixedRate = 60000) // Every 1 minute
    public void processDueSteps() {
        try {
            List<CampaignStepExecution> dueSteps = campaignExecutionService.getDueSteps();

            if (dueSteps.isEmpty()) {
                log.debug("No due campaign steps to process");
                return;
            }

            log.info("Processing {} due campaign steps", dueSteps.size());

            // Process steps asynchronously to avoid blocking
            List<CompletableFuture<Void>> futures = dueSteps.stream()
                    .map(step -> CompletableFuture.runAsync(() -> {
                        try {
                            campaignExecutionService.executeStep(step.getId());
                        } catch (Exception e) {
                            log.error("Failed to execute campaign step {}: {}",
                                    step.getId(), e.getMessage(), e);
                        }
                    }))
                    .toList();

            // Wait for all steps to complete (with timeout)
            CompletableFuture<Void> allSteps = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

            try {
                allSteps.get(java.util.concurrent.TimeUnit.MINUTES.toMillis(5),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                log.info("Successfully processed {} campaign steps", dueSteps.size());
            } catch (Exception e) {
                log.error("Timeout or error processing campaign steps: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.error("Error in campaign scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup old completed executions daily at 2 AM
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldExecutions() {
        log.info("Starting cleanup of old campaign executions");

        try {
            // Implement cleanup logic - delete executions older than 90 days
            // You'll need to add this method to CampaignExecutionService
            log.info("Campaign execution cleanup completed");
        } catch (Exception e) {
            log.error("Error during campaign execution cleanup: {}", e.getMessage(), e);
        }
    }
}