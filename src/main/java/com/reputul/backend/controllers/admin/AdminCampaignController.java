package com.reputul.backend.controllers.admin;

import com.reputul.backend.models.Organization;
import com.reputul.backend.repositories.OrganizationRepository;
import com.reputul.backend.services.campaign.CampaignSequenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for managing campaigns across organizations
 * These endpoints should be secured and only accessible to admin users
 */
@RestController
@RequestMapping("/api/v1/admin/campaigns")
@RequiredArgsConstructor
@Slf4j
public class AdminCampaignController {

    private final CampaignSequenceService campaignSequenceService;
    private final OrganizationRepository organizationRepository;

    /**
     * Initialize preset campaigns for a specific organization
     * Useful for existing orgs that were created before preset campaigns existed
     */
    @PostMapping("/initialize/{orgId}")
    // @PreAuthorize("hasRole('ADMIN')") // Uncomment when you have admin role setup
    public ResponseEntity<Map<String, Object>> initializeCampaignsForOrg(@PathVariable Long orgId) {
        log.info("Admin request: Initializing preset campaigns for org {}", orgId);

        try {
            // Verify org exists
            Organization org = organizationRepository.findById(orgId)
                    .orElseThrow(() -> new RuntimeException("Organization not found: " + orgId));

            // Create preset campaigns
            campaignSequenceService.createPresetCampaigns(orgId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Preset campaigns initialized successfully");
            response.put("organizationId", orgId);
            response.put("organizationName", org.getName());

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            // Org already has campaigns
            log.info("Org {} already has campaigns: {}", orgId, e.getMessage());

            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            response.put("organizationId", orgId);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to initialize campaigns for org {}: {}", orgId, e.getMessage(), e);

            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Failed to initialize campaigns: " + e.getMessage());
            response.put("organizationId", orgId);

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Initialize preset campaigns for ALL organizations that don't have campaigns yet
     * Useful for one-time migration when rolling out preset campaigns
     */
    @PostMapping("/initialize-all")
    // @PreAuthorize("hasRole('ADMIN')") // Uncomment when you have admin role setup
    public ResponseEntity<Map<String, Object>> initializeCampaignsForAllOrgs() {
        log.info("Admin request: Initializing preset campaigns for all organizations");

        List<Organization> allOrgs = organizationRepository.findAll();

        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (Organization org : allOrgs) {
            try {
                campaignSequenceService.createPresetCampaigns(org.getId());
                successCount++;
                log.info("✓ Initialized campaigns for org {}: {}", org.getId(), org.getName());
            } catch (IllegalStateException e) {
                // Org already has campaigns - this is fine
                skippedCount++;
                log.info("- Skipped org {} (already has campaigns): {}", org.getId(), org.getName());
            } catch (Exception e) {
                failedCount++;
                log.error("✗ Failed to initialize campaigns for org {}: {}", org.getId(), e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("totalOrganizations", allOrgs.size());
        response.put("initialized", successCount);
        response.put("skipped", skippedCount);
        response.put("failed", failedCount);
        response.put("message", String.format(
                "Processed %d organizations: %d initialized, %d skipped, %d failed",
                allOrgs.size(), successCount, skippedCount, failedCount
        ));

        log.info("Campaign initialization complete: {} initialized, {} skipped, {} failed",
                successCount, skippedCount, failedCount);

        return ResponseEntity.ok(response);
    }

    /**
     * Get statistics about campaign coverage across all organizations
     */
    @GetMapping("/stats")
    // @PreAuthorize("hasRole('ADMIN')") // Uncomment when you have admin role setup
    public ResponseEntity<Map<String, Object>> getCampaignStats() {
        log.info("Admin request: Getting campaign statistics");

        List<Organization> allOrgs = organizationRepository.findAll();

        int orgsWithCampaigns = 0;
        int orgsWithoutCampaigns = 0;

        for (Organization org : allOrgs) {
            long campaignCount = campaignSequenceService.getSequencesWithSteps(org.getId()).size();
            if (campaignCount > 0) {
                orgsWithCampaigns++;
            } else {
                orgsWithoutCampaigns++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("totalOrganizations", allOrgs.size());
        response.put("organizationsWithCampaigns", orgsWithCampaigns);
        response.put("organizationsWithoutCampaigns", orgsWithoutCampaigns);
        response.put("coveragePercentage", allOrgs.isEmpty() ? 0 :
                (double) orgsWithCampaigns / allOrgs.size() * 100);

        return ResponseEntity.ok(response);
    }
}