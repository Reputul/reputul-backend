-- V010__Automation_Engine_Schema.sql
-- Comprehensive Automation Engine for Review Request Workflows

-- =========================
-- 1) WORKFLOW DEFINITIONS
-- =========================

CREATE TABLE automation_workflows (
                                      id                  BIGSERIAL PRIMARY KEY,
                                      organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                      business_id         BIGINT REFERENCES businesses(id) ON DELETE CASCADE, -- NULL = applies to all businesses

                                      name                VARCHAR(100) NOT NULL,
                                      description         TEXT,

    -- Workflow configuration
                                      trigger_type        VARCHAR(50) NOT NULL, -- 'TIME_BASED', 'EVENT_BASED', 'MANUAL', 'WEBHOOK'
                                      trigger_config      JSONB NOT NULL, -- Trigger-specific configuration

    -- Workflow settings
                                      is_active           BOOLEAN DEFAULT true,
                                      max_executions      INTEGER, -- NULL = unlimited
                                      execution_count     INTEGER DEFAULT 0,

    -- Template and content settings
                                      delivery_method     VARCHAR(20) NOT NULL DEFAULT 'EMAIL', -- 'EMAIL', 'SMS', 'BOTH'
                                      email_template_id   BIGINT REFERENCES email_templates(id),
                                      sms_template        TEXT, -- SMS message template

    -- Timing and conditions
                                      execution_window    JSONB, -- Time windows when workflow can run
                                      conditions          JSONB, -- Additional conditions (customer segments, etc.)

                                      created_at          TIMESTAMPTZ DEFAULT now(),
                                      updated_at          TIMESTAMPTZ DEFAULT now(),
                                      created_by          BIGINT REFERENCES users(id)
);

-- =========================
-- 2) WORKFLOW STEPS/SEQUENCES
-- =========================

CREATE TABLE automation_workflow_steps (
                                           id              BIGSERIAL PRIMARY KEY,
                                           workflow_id     BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,

                                           step_order      INTEGER NOT NULL, -- 1, 2, 3...
                                           step_type       VARCHAR(50) NOT NULL, -- 'SEND_REQUEST', 'WAIT', 'CONDITION', 'WEBHOOK'

    -- Step configuration
                                           step_config     JSONB NOT NULL, -- Step-specific settings

    -- Conditional logic
                                           condition_type  VARCHAR(50), -- 'RATING_BASED', 'STATUS_BASED', 'TIME_BASED'
                                           condition_data  JSONB, -- Condition parameters

    -- Next step routing
                                           success_next_step   INTEGER, -- Next step if condition passes
                                           failure_next_step   INTEGER, -- Next step if condition fails

                                           created_at      TIMESTAMPTZ DEFAULT now()
);

-- =========================
-- 3) AUTOMATION EXECUTIONS (TRACKING)
-- =========================

CREATE TABLE automation_executions (
                                       id                  BIGSERIAL PRIMARY KEY,
                                       workflow_id         BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,
                                       customer_id         BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                                       business_id         BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,

    -- Execution tracking
                                       trigger_event       VARCHAR(100) NOT NULL, -- What triggered this execution
                                       trigger_data        JSONB, -- Event-specific data

                                       status              VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- 'PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'PAUSED'
                                       current_step        INTEGER DEFAULT 1,

    -- Execution timeline
                                       scheduled_for       TIMESTAMPTZ, -- When to execute (for scheduled workflows)
                                       started_at          TIMESTAMPTZ,
                                       completed_at        TIMESTAMPTZ,
                                       next_execution_at   TIMESTAMPTZ, -- For multi-step workflows

    -- Results and error handling
                                       results             JSONB, -- Step execution results
                                       error_message       TEXT,
                                       retry_count         INTEGER DEFAULT 0,
                                       max_retries         INTEGER DEFAULT 3,

                                       created_at          TIMESTAMPTZ DEFAULT now()
);

-- =========================
-- 4) AUTOMATION TRIGGERS
-- =========================

CREATE TABLE automation_triggers (
                                     id                  BIGSERIAL PRIMARY KEY,
                                     organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                     workflow_id         BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,

                                     trigger_name        VARCHAR(100) NOT NULL,
                                     trigger_type        VARCHAR(50) NOT NULL,

    -- Trigger conditions
                                     conditions          JSONB NOT NULL, -- When to fire this trigger

    -- Event-based triggers
                                     event_type          VARCHAR(100), -- 'CUSTOMER_ADDED', 'REVIEW_COMPLETED', 'WEBHOOK_RECEIVED'
                                     event_source        VARCHAR(100), -- 'INTERNAL', 'ZAPIER', 'API', 'WEBHOOK'

    -- Time-based triggers
                                     schedule_expression VARCHAR(100), -- Cron expression or simple schedule
                                     timezone            VARCHAR(50) DEFAULT 'UTC',

    -- Status
                                     is_active           BOOLEAN DEFAULT true,
                                     last_triggered_at   TIMESTAMPTZ,

                                     created_at          TIMESTAMPTZ DEFAULT now()
);

-- =========================
-- 5) AUTOMATION LOGS
-- =========================

CREATE TABLE automation_logs (
                                 id                  BIGSERIAL PRIMARY KEY,
                                 execution_id        BIGINT NOT NULL REFERENCES automation_executions(id) ON DELETE CASCADE,
                                 workflow_id         BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,

                                 log_level           VARCHAR(20) NOT NULL, -- 'INFO', 'WARN', 'ERROR', 'DEBUG'
                                 step_number         INTEGER,

                                 message             TEXT NOT NULL,
                                 details             JSONB, -- Additional structured data

                                 created_at          TIMESTAMPTZ DEFAULT now()
);

-- =========================
-- 6) CUSTOMER WORKFLOW STATES
-- =========================

CREATE TABLE customer_workflow_states (
                                          id                  BIGSERIAL PRIMARY KEY,
                                          customer_id         BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                                          workflow_id         BIGINT NOT NULL REFERENCES automation_workflows(id) ON DELETE CASCADE,

    -- Current state in workflow
                                          current_step        INTEGER DEFAULT 0,
                                          status              VARCHAR(50) DEFAULT 'ELIGIBLE', -- 'ELIGIBLE', 'ACTIVE', 'COMPLETED', 'PAUSED', 'EXCLUDED'

    -- Workflow history
                                          last_executed_step  INTEGER,
                                          last_execution_at   TIMESTAMPTZ,
                                          completion_count    INTEGER DEFAULT 0,

    -- State data
                                          state_data          JSONB, -- Workflow-specific state information

                                          created_at          TIMESTAMPTZ DEFAULT now(),
                                          updated_at          TIMESTAMPTZ DEFAULT now(),

                                          UNIQUE(customer_id, workflow_id)
);

-- =========================
-- 7) WORKFLOW TEMPLATES (PREDEFINED)
-- =========================

CREATE TABLE workflow_templates (
                                    id                  BIGSERIAL PRIMARY KEY,

                                    name                VARCHAR(100) NOT NULL,
                                    description         TEXT NOT NULL,
                                    category            VARCHAR(50) NOT NULL, -- 'REVIEW_REQUEST', 'FOLLOW_UP', 'FEEDBACK'

    -- Template definition
                                    template_config     JSONB NOT NULL, -- Complete workflow configuration

    -- Metadata
                                    is_system_template  BOOLEAN DEFAULT false,
                                    popularity_score    INTEGER DEFAULT 0,

                                    created_at          TIMESTAMPTZ DEFAULT now()
);

-- =========================
-- 8) INDEXES FOR PERFORMANCE
-- =========================

-- Primary workflow lookups
CREATE INDEX idx_automation_workflows_org_business ON automation_workflows(organization_id, business_id);
CREATE INDEX idx_automation_workflows_active ON automation_workflows(is_active) WHERE is_active = true;

-- Execution tracking
CREATE INDEX idx_automation_executions_status ON automation_executions(status);
CREATE INDEX idx_automation_executions_scheduled ON automation_executions(scheduled_for) WHERE scheduled_for IS NOT NULL;
CREATE INDEX idx_automation_executions_customer_workflow ON automation_executions(customer_id, workflow_id);

-- Customer states
CREATE INDEX idx_customer_workflow_states_status ON customer_workflow_states(status);
CREATE INDEX idx_customer_workflow_states_workflow ON customer_workflow_states(workflow_id);

-- Triggers
CREATE INDEX idx_automation_triggers_active ON automation_triggers(is_active) WHERE is_active = true;
CREATE INDEX idx_automation_triggers_type ON automation_triggers(trigger_type);

-- Logs
CREATE INDEX idx_automation_logs_execution ON automation_logs(execution_id);
CREATE INDEX idx_automation_logs_created_at ON automation_logs(created_at);

-- =========================
-- 9) FUNCTIONS FOR AUTOMATION
-- =========================

-- Function to get next scheduled execution
CREATE OR REPLACE FUNCTION get_next_scheduled_executions(limit_count INTEGER DEFAULT 100)
    RETURNS TABLE (
                      execution_id BIGINT,
                      workflow_id BIGINT,
                      customer_id BIGINT,
                      scheduled_for TIMESTAMPTZ
                  ) AS $$
BEGIN
    RETURN QUERY
        SELECT
            ae.id,
            ae.workflow_id,
            ae.customer_id,
            ae.scheduled_for
        FROM automation_executions ae
        WHERE ae.status = 'PENDING'
          AND ae.scheduled_for <= now()
        ORDER BY ae.scheduled_for ASC
        LIMIT limit_count;
END;
$$ LANGUAGE plpgsql;

-- Function to check if customer is eligible for workflow
CREATE OR REPLACE FUNCTION is_customer_eligible_for_workflow(
    p_customer_id BIGINT,
    p_workflow_id BIGINT
)
    RETURNS BOOLEAN AS $$
DECLARE
    workflow_config RECORD;
    customer_state RECORD;
    is_eligible BOOLEAN := true;
BEGIN
    -- Get workflow configuration
    SELECT * INTO workflow_config
    FROM automation_workflows
    WHERE id = p_workflow_id AND is_active = true;

    IF NOT FOUND THEN
        RETURN false;
    END IF;

    -- Get customer workflow state
    SELECT * INTO customer_state
    FROM customer_workflow_states
    WHERE customer_id = p_customer_id AND workflow_id = p_workflow_id;

    -- Check if customer is excluded or has completed max executions
    IF FOUND THEN
        IF customer_state.status = 'EXCLUDED' THEN
            RETURN false;
        END IF;

        -- Check completion limits
        IF workflow_config.max_executions IS NOT NULL
            AND customer_state.completion_count >= workflow_config.max_executions THEN
            RETURN false;
        END IF;
    END IF;

    RETURN is_eligible;
END;
$$ LANGUAGE plpgsql;

-- Function to create workflow execution
CREATE OR REPLACE FUNCTION create_workflow_execution(
    p_workflow_id BIGINT,
    p_customer_id BIGINT,
    p_trigger_event VARCHAR(100),
    p_trigger_data JSONB DEFAULT NULL,
    p_scheduled_for TIMESTAMPTZ DEFAULT NULL
)
    RETURNS BIGINT AS $$
DECLARE
    execution_id BIGINT;
    workflow_record RECORD;
BEGIN
    -- Get workflow and business info
    SELECT w.*, b.id as business_id
    INTO workflow_record
    FROM automation_workflows w
             LEFT JOIN customers c ON c.id = p_customer_id
             LEFT JOIN businesses b ON b.id = c.business_id OR b.id = w.business_id
    WHERE w.id = p_workflow_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Workflow not found or customer not associated with business';
    END IF;

    -- Create execution record
    INSERT INTO automation_executions (
        workflow_id,
        customer_id,
        business_id,
        trigger_event,
        trigger_data,
        scheduled_for,
        status
    ) VALUES (
                 p_workflow_id,
                 p_customer_id,
                 workflow_record.business_id,
                 p_trigger_event,
                 p_trigger_data,
                 COALESCE(p_scheduled_for, now()),
                 CASE WHEN p_scheduled_for IS NULL THEN 'RUNNING' ELSE 'PENDING' END
             ) RETURNING id INTO execution_id;

    -- Update workflow execution count
    UPDATE automation_workflows
    SET execution_count = execution_count + 1
    WHERE id = p_workflow_id;

    RETURN execution_id;
END;
$$ LANGUAGE plpgsql;

-- =========================
-- 10) TRIGGERS FOR AUDIT TRAIL
-- =========================

CREATE TRIGGER automation_workflows_updated_at_trigger
    BEFORE UPDATE ON automation_workflows
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER customer_workflow_states_updated_at_trigger
    BEFORE UPDATE ON customer_workflow_states
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- =========================
-- 11) SAMPLE DATA - COMMON WORKFLOW TEMPLATES
-- =========================

-- Template: Basic Review Request (Send after service completion)
INSERT INTO workflow_templates (name, description, category, is_system_template, template_config) VALUES
                                                                                                      ('Basic Review Request', 'Send review request 24 hours after service completion', 'REVIEW_REQUEST', true,
                                                                                                       '{"trigger_type":"TIME_BASED","delay_hours":24,"delivery_method":"EMAIL","max_executions":1,"steps":[{"step_type":"SEND_REQUEST","step_order":1}]}'::jsonb),

-- Template: 3-Step Follow-up Sequence
                                                                                                      ('3-Step Follow-up', 'Initial request + 2 follow-ups (3 days, 7 days)', 'FOLLOW_UP', true,
                                                                                                       '{"trigger_type":"TIME_BASED","delay_hours":24,"delivery_method":"EMAIL","steps":[{"step_type":"SEND_REQUEST","step_order":1},{"step_type":"WAIT","step_order":2,"wait_days":3},{"step_type":"SEND_REQUEST","step_order":3,"template_type":"follow_up"},{"step_type":"WAIT","step_order":4,"wait_days":7},{"step_type":"SEND_REQUEST","step_order":5,"template_type":"final_follow_up"}]}'::jsonb),

-- Template: SMS + Email Combo
                                                                                                      ('Multi-Channel Request', 'SMS immediately, email follow-up if no response', 'REVIEW_REQUEST', true,
                                                                                                       '{"trigger_type":"TIME_BASED","delay_hours":2,"delivery_method":"SMS","steps":[{"step_type":"SEND_REQUEST","step_order":1,"method":"SMS"},{"step_type":"WAIT","step_order":2,"wait_days":2},{"step_type":"CONDITION","step_order":3,"condition_type":"STATUS_BASED","condition":"not_responded"},{"step_type":"SEND_REQUEST","step_order":4,"method":"EMAIL"}]}'::jsonb);

-- =========================
-- 12) COMMENTS FOR DOCUMENTATION
-- =========================

COMMENT ON TABLE automation_workflows IS 'Main workflow definitions with triggers and configuration';
COMMENT ON TABLE automation_workflow_steps IS 'Individual steps within workflows for complex sequences';
COMMENT ON TABLE automation_executions IS 'Tracking of workflow executions per customer';
COMMENT ON TABLE automation_triggers IS 'Event-based and time-based trigger definitions';
COMMENT ON TABLE customer_workflow_states IS 'Customer progress through workflows';
COMMENT ON TABLE workflow_templates IS 'Predefined workflow templates for easy setup';

COMMENT ON COLUMN automation_workflows.trigger_config IS 'JSON config: {"delay_hours": 24, "conditions": {...}}';
COMMENT ON COLUMN automation_executions.trigger_data IS 'Data that triggered execution: customer added, service completed, etc.';
COMMENT ON COLUMN customer_workflow_states.state_data IS 'Workflow-specific customer data and progress';

-- =========================
-- 13) PERFORMANCE MONITORING QUERIES (AS COMMENTS)
-- =========================

/*
-- Check workflow performance:
SELECT
    w.name,
    COUNT(e.id) as total_executions,
    COUNT(CASE WHEN e.status = 'COMPLETED' THEN 1 END) as completed,
    COUNT(CASE WHEN e.status = 'FAILED' THEN 1 END) as failed,
    AVG(EXTRACT(EPOCH FROM (e.completed_at - e.started_at))/60) as avg_completion_minutes
FROM automation_workflows w
LEFT JOIN automation_executions e ON e.workflow_id = w.id
WHERE w.created_at > NOW() - INTERVAL '30 days'
GROUP BY w.id, w.name
ORDER BY total_executions DESC;

-- Find stuck executions:
SELECT * FROM automation_executions
WHERE status = 'RUNNING'
AND started_at < NOW() - INTERVAL '1 hour';

-- Customer workflow completion rates:
SELECT
    w.name,
    COUNT(DISTINCT cws.customer_id) as customers_enrolled,
    COUNT(CASE WHEN cws.status = 'COMPLETED' THEN 1 END) as customers_completed,
    COUNT(CASE WHEN cws.status = 'COMPLETED' THEN 1 END) * 100.0 / COUNT(DISTINCT cws.customer_id) as completion_rate
FROM automation_workflows w
JOIN customer_workflow_states cws ON cws.workflow_id = w.id
GROUP BY w.id, w.name;
*/