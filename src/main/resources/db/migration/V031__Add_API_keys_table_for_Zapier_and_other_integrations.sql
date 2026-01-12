-- Add API keys table for Zapier and other integrations
CREATE TABLE IF NOT EXISTS api_keys (
                                        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                        organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                        name VARCHAR(255) NOT NULL,
                                        key_hash VARCHAR(255) NOT NULL UNIQUE,
                                        key_prefix VARCHAR(20) NOT NULL, -- First few chars for UI display (e.g., "rpt_live_abc...")
                                        last_used_at TIMESTAMP,
                                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        created_by BIGINT NOT NULL REFERENCES users(id),
                                        revoked_at TIMESTAMP,
                                        revoked_by BIGINT REFERENCES users(id),

                                        CONSTRAINT unique_org_key_name UNIQUE (organization_id, name)
);

CREATE INDEX IF NOT EXISTS idx_api_keys_org ON api_keys(organization_id);
CREATE INDEX IF NOT EXISTS idx_api_keys_hash ON api_keys(key_hash) WHERE revoked_at IS NULL;

-- Add default business flag to businesses table
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT false;

-- Set first business per org as default (only if not already set)
WITH first_businesses AS (
    SELECT DISTINCT ON (organization_id) id, organization_id
    FROM businesses
    ORDER BY organization_id, created_at ASC
)
UPDATE businesses
SET is_default = true
WHERE id IN (SELECT id FROM first_businesses)
  AND is_default = false;  -- Only update if not already default

-- Ensure only one default per org
DROP INDEX IF EXISTS idx_businesses_default_per_org;
CREATE UNIQUE INDEX idx_businesses_default_per_org ON businesses(organization_id) WHERE is_default = true;

-- Add idempotency keys table for webhook deduplication
CREATE TABLE IF NOT EXISTS idempotency_keys (
                                                key VARCHAR(255) PRIMARY KEY,
                                                organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                                request_path VARCHAR(500) NOT NULL,
                                                response_status INT NOT NULL,
                                                response_body TEXT NOT NULL,
                                                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                                expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires ON idempotency_keys(expires_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_org ON idempotency_keys(organization_id);

-- Add frequency tracking to review_requests to enforce 30-day limit
ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS recipient_email VARCHAR(255);
ALTER TABLE review_requests ADD COLUMN IF NOT EXISTS recipient_phone VARCHAR(50);

-- Drop old indexes if they exist (wrong column names)
DROP INDEX IF EXISTS idx_review_requests_email_sent;
DROP INDEX IF EXISTS idx_review_requests_phone_sent;

-- Create correct indexes on recipient_email and recipient_phone with created_at
CREATE INDEX IF NOT EXISTS idx_review_requests_email_created ON review_requests(business_id, recipient_email, created_at) WHERE recipient_email IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_review_requests_phone_created ON review_requests(business_id, recipient_phone, created_at) WHERE recipient_phone IS NOT NULL;

COMMENT ON TABLE api_keys IS 'API keys for external integrations (Zapier, webhooks, etc.)';
COMMENT ON TABLE idempotency_keys IS 'Prevents duplicate webhook processing within 24-hour window';
COMMENT ON COLUMN businesses.is_default IS 'Default business for API requests that do not specify business_id';