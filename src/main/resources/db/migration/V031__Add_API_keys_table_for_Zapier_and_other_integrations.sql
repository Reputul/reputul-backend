-- Add API keys table for Zapier and other integrations
CREATE TABLE api_keys (
                          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                          name VARCHAR(255) NOT NULL,
                          key_hash VARCHAR(255) NOT NULL UNIQUE,
                          key_prefix VARCHAR(20) NOT NULL, -- First few chars for UI display (e.g., "rpt_live_abc...")
                          last_used_at TIMESTAMP,
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          created_by UUID NOT NULL REFERENCES users(id),
                          revoked_at TIMESTAMP,
                          revoked_by UUID REFERENCES users(id),

                          CONSTRAINT unique_org_key_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_api_keys_org ON api_keys(organization_id);
CREATE INDEX idx_api_keys_hash ON api_keys(key_hash) WHERE revoked_at IS NULL;

-- Add default business flag to businesses table
ALTER TABLE businesses ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT false;

-- Set first business per org as default
WITH first_businesses AS (
    SELECT DISTINCT ON (organization_id) id, organization_id
    FROM businesses
    ORDER BY organization_id, created_at ASC
)
UPDATE businesses
SET is_default = true
WHERE id IN (SELECT id FROM first_businesses);

-- Ensure only one default per org
CREATE UNIQUE INDEX idx_businesses_default_per_org ON businesses(organization_id) WHERE is_default = true;

-- Add idempotency keys table for webhook deduplication
CREATE TABLE idempotency_keys (
                                  key VARCHAR(255) PRIMARY KEY,
                                  organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                  request_path VARCHAR(500) NOT NULL,
                                  response_status INT NOT NULL,
                                  response_body TEXT NOT NULL,
                                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_idempotency_keys_expires ON idempotency_keys(expires_at);
CREATE INDEX idx_idempotency_keys_org ON idempotency_keys(organization_id);

-- Add frequency tracking to review_requests to enforce 30-day limit
ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255);
ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS customer_phone VARCHAR(50);

CREATE INDEX idx_review_requests_email_sent ON review_requests(customer_email, sent_at) WHERE customer_email IS NOT NULL;
CREATE INDEX idx_review_requests_phone_sent ON review_requests(customer_phone, sent_at) WHERE customer_phone IS NOT NULL;

COMMENT ON TABLE api_keys IS 'API keys for external integrations (Zapier, webhooks, etc.)';
COMMENT ON TABLE idempotency_keys IS 'Prevents duplicate webhook processing within 24-hour window';
COMMENT ON COLUMN businesses.is_default IS 'Default business for API requests that do not specify business_id';