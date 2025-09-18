-- V011__Enhanced_Automation_Schema.sql
-- Enhanced automation schema with proper relationships and indexing
-- FIXED: Properly handles existing V010 workflow_templates table structure

-- Update workflow templates table to add missing columns from V011 structure
DO $$
    BEGIN
        -- The table exists from V010, so we need to add the missing columns
        IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'workflow_templates') THEN
            -- Add missing columns that V011 needs
            ALTER TABLE workflow_templates
                ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true,
                ADD COLUMN IF NOT EXISTS trigger_type VARCHAR(50),
                ADD COLUMN IF NOT EXISTS default_actions JSONB,
                ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

            -- Set default trigger_type for existing rows
            UPDATE workflow_templates
            SET trigger_type = 'SERVICE_COMPLETED'
            WHERE trigger_type IS NULL;

            -- Add NOT NULL constraint after setting default values
            ALTER TABLE workflow_templates
                ALTER COLUMN trigger_type SET NOT NULL;
        ELSE
            -- Create fresh table if it somehow doesn't exist
            CREATE TABLE workflow_templates (
                                                id BIGSERIAL PRIMARY KEY,
                                                name VARCHAR(255) NOT NULL,
                                                description TEXT,
                                                category VARCHAR(100),
                                                is_active BOOLEAN NOT NULL DEFAULT true,
                                                trigger_type VARCHAR(50) NOT NULL,
                                                template_config JSONB,
                                                default_actions JSONB,
                                                is_system_template BOOLEAN DEFAULT false,
                                                popularity_score INTEGER DEFAULT 0,
                                                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                                                updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            );
        END IF;
    END $$;

-- Create automation workflows table (updated)
-- Note: This extends the existing table if it exists, or creates it if it doesn't
DO $$
    BEGIN
        -- Check if automation_workflows table exists and update it, or create it fresh
        IF NOT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'automation_workflows') THEN
            CREATE TABLE automation_workflows (
                                                  id BIGSERIAL PRIMARY KEY,
                                                  name VARCHAR(255) NOT NULL,
                                                  description TEXT,
                                                  organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                                  business_id BIGINT REFERENCES businesses(id) ON DELETE CASCADE,
                                                  is_active BOOLEAN NOT NULL DEFAULT true,
                                                  trigger_type VARCHAR(50) NOT NULL,
                                                  trigger_config JSONB,
                                                  actions JSONB,
                                                  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                                                  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            );
        ELSE
            -- Add any missing columns to existing table
            ALTER TABLE automation_workflows
                ADD COLUMN IF NOT EXISTS business_id BIGINT REFERENCES businesses(id) ON DELETE CASCADE,
                ADD COLUMN IF NOT EXISTS actions JSONB;
        END IF;
    END $$;

-- Create automation executions table (updated)
DO $$
    BEGIN
        IF NOT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'automation_executions') THEN
            CREATE TABLE automation_executions (
                                                   id BIGSERIAL PRIMARY KEY,
                                                   workflow_id BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,
                                                   customer_id BIGINT REFERENCES customers(id) ON DELETE SET NULL,
                                                   status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                                   trigger_event VARCHAR(100),
                                                   trigger_data JSONB,
                                                   execution_data JSONB,
                                                   error_message TEXT,
                                                   started_at TIMESTAMP WITH TIME ZONE,
                                                   completed_at TIMESTAMP WITH TIME ZONE,
                                                   created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                                                   updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            );
        ELSE
            -- Add any missing columns
            ALTER TABLE automation_executions
                ADD COLUMN IF NOT EXISTS execution_data JSONB,
                ADD COLUMN IF NOT EXISTS error_message TEXT;
        END IF;
    END $$;

-- Create automation triggers table (updated with better structure)
DO $$
    BEGIN
        IF NOT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'automation_triggers') THEN
            CREATE TABLE automation_triggers (
                                                 id BIGSERIAL PRIMARY KEY,
                                                 organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                                 event_type VARCHAR(100) NOT NULL,
                                                 is_active BOOLEAN NOT NULL DEFAULT true,
                                                 conditions JSONB,
                                                 trigger_config JSONB,
                                                 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
                                                 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
            );
        ELSE
            -- Add any missing columns
            ALTER TABLE automation_triggers
                ADD COLUMN IF NOT EXISTS trigger_config JSONB;
        END IF;
    END $$;

-- Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_workflow_templates_active ON workflow_templates(is_active, category);
CREATE INDEX IF NOT EXISTS idx_workflow_templates_trigger_type ON workflow_templates(trigger_type, is_active);

CREATE INDEX IF NOT EXISTS idx_automation_workflows_org ON automation_workflows(organization_id, is_active);
CREATE INDEX IF NOT EXISTS idx_automation_workflows_business ON automation_workflows(business_id, is_active);
CREATE INDEX IF NOT EXISTS idx_automation_workflows_trigger_type ON automation_workflows(trigger_type, is_active);

CREATE INDEX IF NOT EXISTS idx_automation_executions_workflow ON automation_executions(workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_automation_executions_customer ON automation_executions(customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_automation_executions_status ON automation_executions(status, created_at);

CREATE INDEX IF NOT EXISTS idx_automation_triggers_org ON automation_triggers(organization_id, is_active);
CREATE INDEX IF NOT EXISTS idx_automation_triggers_event_type ON automation_triggers(event_type, is_active);

-- Insert some default workflow templates (only if they don't exist)
-- Note: We'll insert new ones but keep existing V010 templates
INSERT INTO workflow_templates (name, description, category, trigger_type, template_config, default_actions)
SELECT * FROM (VALUES
                   ('Review Request Follow-up', 'Automatically send review requests 3 days after service completion', 'review_automation', 'SERVICE_COMPLETED',
                    '{"delay_days": 3, "conditions": {"service_status": "completed"}}'::jsonb,
                    '{"send_email": true, "template_type": "review_request", "delivery_method": "EMAIL"}'::jsonb),

                   ('SMS Review Reminder', 'Send SMS reminder for customers who haven''t responded to email requests', 'review_automation', 'REVIEW_COMPLETED',
                    '{"delay_days": 7, "conditions": {"no_review_response": true}}'::jsonb,
                    '{"send_sms": true, "template_type": "review_reminder", "delivery_method": "SMS"}'::jsonb),

                   ('Thank You Follow-up', 'Send thank you message after positive review submission', 'customer_retention', 'REVIEW_COMPLETED',
                    '{"conditions": {"rating": ">=4"}}'::jsonb,
                    '{"send_email": true, "template_type": "thank_you", "delivery_method": "EMAIL"}'::jsonb),

                   ('New Customer Welcome', 'Welcome new customers and set expectations', 'customer_onboarding', 'CUSTOMER_CREATED',
                    '{"delay_hours": 1}'::jsonb,
                    '{"send_email": true, "template_type": "welcome", "delivery_method": "EMAIL"}'::jsonb)
              ) AS v(name, description, category, trigger_type, template_config, default_actions)
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_templates wt
    WHERE wt.name = v.name AND wt.trigger_type = v.trigger_type
);

-- Add constraints for data integrity (only if they don't exist)
DO $$
    BEGIN
        -- Execution status must allow legacy + new values
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'chk_execution_status'
              AND table_name = 'automation_executions'
        ) THEN
            ALTER TABLE automation_executions
                ADD CONSTRAINT chk_execution_status
                    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','PAUSED'));
        END IF;

        -- Trigger type must allow legacy + new values
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'chk_trigger_type'
              AND table_name = 'automation_workflows'
        ) THEN
            ALTER TABLE automation_workflows
                ADD CONSTRAINT chk_trigger_type
                    CHECK (trigger_type IN (
                                            'CUSTOMER_CREATED','SERVICE_COMPLETED','REVIEW_COMPLETED','SCHEDULED','WEBHOOK',
                                            'TIME_BASED','EVENT_BASED','MANUAL'
                        ));
        END IF;
    END $$;


-- Add updated_at trigger for automatic timestamp updates
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply updated_at triggers to automation tables
DROP TRIGGER IF EXISTS update_workflow_templates_updated_at ON workflow_templates;
CREATE TRIGGER update_workflow_templates_updated_at BEFORE UPDATE ON workflow_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_automation_workflows_updated_at ON automation_workflows;
CREATE TRIGGER update_automation_workflows_updated_at BEFORE UPDATE ON automation_workflows
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_automation_executions_updated_at ON automation_executions;
CREATE TRIGGER update_automation_executions_updated_at BEFORE UPDATE ON automation_executions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

DROP TRIGGER IF EXISTS update_automation_triggers_updated_at ON automation_triggers;
CREATE TRIGGER update_automation_triggers_updated_at BEFORE UPDATE ON automation_triggers
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();