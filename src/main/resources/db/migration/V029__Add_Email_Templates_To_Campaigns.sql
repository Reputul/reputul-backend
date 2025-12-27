-- V029: Add Email Template Integration to Campaign Steps
-- This allows campaign email steps to use the email template system for consistent branding

-- Add email_template_id column to campaign_steps
ALTER TABLE campaign_steps
    ADD COLUMN email_template_id BIGINT;

-- Add foreign key constraint
ALTER TABLE campaign_steps
    ADD CONSTRAINT fk_campaign_step_email_template
        FOREIGN KEY (email_template_id)
            REFERENCES email_templates(id)
            ON DELETE SET NULL;

-- Make body_template nullable since email steps will use templates
-- SMS steps will still use body_template
ALTER TABLE campaign_steps
    ALTER COLUMN body_template DROP NOT NULL;

-- Add index for performance
CREATE INDEX idx_campaign_step_email_template
    ON campaign_steps(email_template_id);

-- Comments for clarity
COMMENT ON COLUMN campaign_steps.email_template_id IS 'Reference to email_templates for EMAIL message types. NULL for SMS or inline templates.';
COMMENT ON COLUMN campaign_steps.body_template IS 'Template body text. Used for SMS messages or fallback. Emails should use email_template_id instead.';