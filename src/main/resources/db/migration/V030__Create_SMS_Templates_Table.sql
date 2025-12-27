-- V030: Create SMS Templates Table (Future-Ready)
-- This table is ready for when you want to add SMS template system
-- For now, SMS uses inline bodyTemplate in campaign_steps

CREATE TABLE IF NOT EXISTS sms_templates (
                                             id BIGSERIAL PRIMARY KEY,
                                             user_id BIGINT NOT NULL,
                                             name VARCHAR(255) NOT NULL,
                                             body TEXT NOT NULL,
                                             is_active BOOLEAN DEFAULT true,
                                             is_default BOOLEAN DEFAULT false,
                                             created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                             updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                             CONSTRAINT fk_sms_template_user FOREIGN KEY (user_id)
                                                 REFERENCES users(id) ON DELETE CASCADE
);

-- Indexes for performance
CREATE INDEX idx_sms_template_user ON sms_templates(user_id);
CREATE INDEX idx_sms_template_active ON sms_templates(user_id, is_active);
CREATE INDEX idx_sms_template_default ON sms_templates(user_id, is_default);

-- Add optional SMS template ID to campaign_steps (for future use)
ALTER TABLE campaign_steps
    ADD COLUMN IF NOT EXISTS sms_template_id BIGINT,
    ADD CONSTRAINT fk_campaign_step_sms_template
        FOREIGN KEY (sms_template_id)
            REFERENCES sms_templates(id)
            ON DELETE SET NULL;

CREATE INDEX idx_campaign_step_sms_template ON campaign_steps(sms_template_id);

-- Comments
COMMENT ON TABLE sms_templates IS 'SMS message templates (future feature - currently inactive)';
COMMENT ON COLUMN campaign_steps.sms_template_id IS 'Optional reference to SMS template. NULL means using inline bodyTemplate.';