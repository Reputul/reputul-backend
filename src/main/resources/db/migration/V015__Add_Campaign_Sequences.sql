CREATE TABLE campaign_sequences (
                                    id BIGSERIAL PRIMARY KEY,
                                    org_id BIGINT NOT NULL,
                                    name VARCHAR(255) NOT NULL,
                                    description TEXT,
                                    is_default BOOLEAN DEFAULT false,
                                    is_active BOOLEAN DEFAULT true,
                                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    CONSTRAINT fk_campaign_sequences_org_id FOREIGN KEY (org_id) REFERENCES organizations(id) ON DELETE CASCADE
);

CREATE INDEX idx_campaign_sequences_org_active ON campaign_sequences(org_id, is_active);

CREATE TABLE campaign_steps (
                                id BIGSERIAL PRIMARY KEY,
                                sequence_id BIGINT NOT NULL,
                                step_number INTEGER NOT NULL,
                                delay_hours INTEGER NOT NULL DEFAULT 0,
                                message_type VARCHAR(50) NOT NULL CHECK (message_type IN ('SMS', 'EMAIL_PROFESSIONAL', 'EMAIL_PLAIN')),
                                subject_template VARCHAR(255),
                                body_template TEXT NOT NULL,
                                is_active BOOLEAN DEFAULT true,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                CONSTRAINT fk_campaign_steps_sequence_id FOREIGN KEY (sequence_id) REFERENCES campaign_sequences(id) ON DELETE CASCADE,
                                CONSTRAINT uk_campaign_steps_sequence_step UNIQUE (sequence_id, step_number)
);

CREATE INDEX idx_campaign_steps_sequence_step ON campaign_steps(sequence_id, step_number);

CREATE TABLE campaign_executions (
                                     id BIGSERIAL PRIMARY KEY,
                                     review_request_id BIGINT NOT NULL,
                                     sequence_id BIGINT NOT NULL,
                                     current_step INTEGER DEFAULT 1,
                                     status VARCHAR(20) DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'FAILED')),
                                     started_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                     completed_at TIMESTAMP NULL,
                                     CONSTRAINT fk_campaign_executions_review_request_id FOREIGN KEY (review_request_id) REFERENCES review_requests(id) ON DELETE CASCADE,
                                     CONSTRAINT fk_campaign_executions_sequence_id FOREIGN KEY (sequence_id) REFERENCES campaign_sequences(id)
);

CREATE INDEX idx_campaign_executions_status_step ON campaign_executions(status, current_step);

CREATE TABLE campaign_step_executions (
                                          id BIGSERIAL PRIMARY KEY,
                                          execution_id BIGINT NOT NULL,
                                          step_id BIGINT NOT NULL,
                                          scheduled_at TIMESTAMP NOT NULL,
                                          sent_at TIMESTAMP NULL,
                                          status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'DELIVERED', 'FAILED', 'SKIPPED')),
                                          error_message TEXT NULL,
                                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                          CONSTRAINT fk_campaign_step_executions_execution_id FOREIGN KEY (execution_id) REFERENCES campaign_executions(id) ON DELETE CASCADE,
                                          CONSTRAINT fk_campaign_step_executions_step_id FOREIGN KEY (step_id) REFERENCES campaign_steps(id)
);

CREATE INDEX idx_campaign_step_executions_scheduled ON campaign_step_executions(scheduled_at, status);

-- Add campaign execution reference to review_requests
ALTER TABLE review_requests
    ADD COLUMN campaign_execution_id BIGINT NULL,
    ADD COLUMN source_trigger VARCHAR(50) DEFAULT 'MANUAL' CHECK (source_trigger IN ('MANUAL', 'CRM_INTEGRATION', 'API', 'BULK_IMPORT'));

ALTER TABLE review_requests
    ADD CONSTRAINT fk_review_requests_campaign_execution_id
        FOREIGN KEY (campaign_execution_id) REFERENCES campaign_executions(id);

-- Create trigger for updated_at timestamp (PostgreSQL doesn't have ON UPDATE CURRENT_TIMESTAMP)
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_campaign_sequences_updated_at
    BEFORE UPDATE ON campaign_sequences
    FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();