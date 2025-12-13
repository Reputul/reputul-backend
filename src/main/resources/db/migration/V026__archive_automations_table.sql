-- V026__Archive_Automation_Tables.sql
-- Mark automation tables as archived - preserving for potential future advanced features
-- Tables remain in database but are not actively used in the application

-- Add comments to automation tables to indicate they are archived
COMMENT ON TABLE automation_workflows IS 'ARCHIVED: Advanced automation workflow builder - preserved for potential future power-user features. Current app uses campaign_sequences for simple preset campaigns.';
COMMENT ON TABLE automation_executions IS 'ARCHIVED: Execution tracking for advanced workflows - preserved for potential future use.';
COMMENT ON TABLE automation_triggers IS 'ARCHIVED: Event triggers for advanced workflows - preserved for potential future use.';
COMMENT ON TABLE automation_logs IS 'ARCHIVED: Detailed logging for workflow executions - preserved for potential future use.';
COMMENT ON TABLE customer_workflow_states IS 'ARCHIVED: Customer state tracking in workflows - preserved for potential future use.';
COMMENT ON TABLE workflow_templates IS 'ARCHIVED: Advanced workflow template library - preserved for potential future use.';

-- Add application_status column to track which system is active
ALTER TABLE campaign_sequences
    ADD COLUMN IF NOT EXISTS application_status VARCHAR(20) DEFAULT 'active';

COMMENT ON COLUMN campaign_sequences.application_status IS 'Indicates if this campaign system is actively used (active) vs archived (inactive)';

-- Mark campaign sequences as the active system
UPDATE campaign_sequences SET application_status = 'active';

-- Add feature flag tracking table for future use
CREATE TABLE IF NOT EXISTS feature_flags (
                                             id BIGSERIAL PRIMARY KEY,
                                             feature_name VARCHAR(100) NOT NULL UNIQUE,
                                             is_enabled BOOLEAN NOT NULL DEFAULT false,
                                             description TEXT,
                                             organization_id BIGINT REFERENCES organizations(id) ON DELETE CASCADE,
                                             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                             updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE feature_flags IS 'Feature flag configuration - allows enabling/disabling features per organization or globally';

-- Insert feature flag for advanced automation (disabled by default)
INSERT INTO feature_flags (feature_name, is_enabled, description, organization_id)
VALUES ('advanced_automation', false, 'Enable advanced workflow automation builder (vs simple preset campaigns)', NULL)
ON CONFLICT (feature_name) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_feature_flags_name_enabled ON feature_flags(feature_name, is_enabled);
CREATE INDEX IF NOT EXISTS idx_feature_flags_org ON feature_flags(organization_id);