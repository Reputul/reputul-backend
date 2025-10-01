package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.repositories.BusinessRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time migration to backfill Wilson Score metrics for existing businesses
 *
 * To run this migration:
 * 1. Add @Component annotation (uncomment it)
 * 2. Restart your application
 * 3. The migration will run once and update all businesses
 * 4. Remove or comment out @Component to prevent re-running
 */
 @Component  // Uncomment this line to run the migration
@Profile("!test") // Don't run in test environment
@Slf4j
public class WilsonScoreBackfillService implements CommandLineRunner {

    private final BusinessRepository businessRepository;
    private final ReputationService reputationService;

    public WilsonScoreBackfillService(BusinessRepository businessRepository,
                                      ReputationService reputationService) {
        this.businessRepository = businessRepository;
        this.reputationService = reputationService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Starting Wilson Score backfill migration for existing businesses...");

        List<Business> allBusinesses = businessRepository.findAll();

        if (allBusinesses.isEmpty()) {
            log.info("No businesses found to migrate.");
            return;
        }

        log.info("Found {} businesses to migrate", allBusinesses.size());

        int successCount = 0;
        int errorCount = 0;

        for (Business business : allBusinesses) {
            try {
                log.info("Migrating business: {} (ID: {})", business.getName(), business.getId());

                // Calculate and update all Wilson Score metrics
                reputationService.updateBusinessReputationAndBadge(business.getId());

                successCount++;
                log.info("✅ Successfully migrated business: {}", business.getName());

                // Small delay to avoid overwhelming the database
                Thread.sleep(100);

            } catch (Exception e) {
                errorCount++;
                log.error("❌ Failed to migrate business: {} (ID: {}). Error: {}",
                        business.getName(), business.getId(), e.getMessage());
            }
        }

        log.info("🎉 Wilson Score migration completed!");
        log.info("✅ Successfully migrated: {} businesses", successCount);
        log.info("❌ Failed migrations: {} businesses", errorCount);

        if (errorCount > 0) {
            log.warn("⚠️  Some businesses failed to migrate. Check logs above for details.");
        }
    }
}