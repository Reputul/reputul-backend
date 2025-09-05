-- V006__Add_Organizations_And_Multi_Tenancy.sql
-- Add Organizations (Workspaces) for multi-tenancy support

-- =========================
-- 1) ORGANIZATIONS TABLE
-- =========================
CREATE TABLE organizations (
                               id                              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               name                            VARCHAR(255) NOT NULL,
                               plan                            VARCHAR(50) NOT NULL DEFAULT 'SOLO',
                               stripe_customer_id              VARCHAR(255) UNIQUE,
                               stripe_subscription_id          VARCHAR(255),
                               settings                        JSONB,
                               sms_phone_number               VARCHAR(50),
                               sms_credits_remaining          INTEGER DEFAULT 0,
                               trial_ends_at                  TIMESTAMPTZ,
                               is_active                      BOOLEAN DEFAULT TRUE,
                               billing_email                  VARCHAR(255),
                               max_businesses                 INTEGER DEFAULT 1,
                               max_users                      INTEGER DEFAULT 1,
                               max_monthly_review_requests    INTEGER DEFAULT 100,
                               created_at                     TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at                     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Indexes for organizations
CREATE INDEX idx_organizations_stripe_customer_id ON organizations(stripe_customer_id);
CREATE INDEX idx_organizations_plan ON organizations(plan);
CREATE INDEX idx_organizations_is_active ON organizations(is_active);
CREATE INDEX idx_organizations_created_at ON organizations(created_at);

-- =========================
-- 2) ADD ORGANIZATION TO USERS
-- =========================
ALTER TABLE users
    ADD COLUMN organization_id BIGINT REFERENCES organizations(id),
    ADD COLUMN role VARCHAR(50) DEFAULT 'STAFF',
    ADD COLUMN invited_by BIGINT REFERENCES users(id),
    ADD COLUMN invited_at TIMESTAMPTZ,
    ADD COLUMN last_login_at TIMESTAMPTZ,
    ADD COLUMN created_at TIMESTAMPTZ DEFAULT now(),  -- FIXED: Add created_at to users since it doesn't exist
    ADD COLUMN updated_at TIMESTAMPTZ DEFAULT now();  -- FIXED: Add updated_at to users

-- Index for user organization lookup
CREATE INDEX idx_users_organization_id ON users(organization_id);
CREATE INDEX idx_users_role ON users(role);

-- =========================
-- 3) ADD ORGANIZATION TO BUSINESSES
-- =========================
ALTER TABLE businesses
    ADD COLUMN organization_id BIGINT REFERENCES organizations(id);

-- Index for business organization lookup
CREATE INDEX idx_businesses_organization_id ON businesses(organization_id);

-- =========================
-- 4) USAGE TRACKING TABLE (separate from usage_events)
-- =========================
CREATE TABLE usage_tracking (
                                id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                organization_id         BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                metric                  VARCHAR(50) NOT NULL,
                                quantity                INTEGER NOT NULL DEFAULT 1,
                                metadata                VARCHAR(255),
                                reference_type          VARCHAR(100),
                                reference_id            BIGINT,
                                period_start           TIMESTAMPTZ,
                                period_end             TIMESTAMPTZ,
                                billed                 BOOLEAN DEFAULT FALSE,
                                stripe_usage_record_id VARCHAR(255),
                                cost_cents             INTEGER,
                                created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

                                CONSTRAINT check_quantity_positive CHECK (quantity > 0)
);

-- Indexes for usage tracking
CREATE INDEX idx_usage_organization_id ON usage_tracking(organization_id);
CREATE INDEX idx_usage_metric ON usage_tracking(metric);
CREATE INDEX idx_usage_billed ON usage_tracking(billed);
CREATE INDEX idx_usage_created_at ON usage_tracking(created_at);
CREATE INDEX idx_usage_period ON usage_tracking(period_start, period_end);
CREATE INDEX idx_usage_reference ON usage_tracking(reference_type, reference_id);

-- Composite indexes for common queries
CREATE INDEX idx_usage_org_metric_period ON usage_tracking(organization_id, metric, created_at);
CREATE INDEX idx_usage_org_unbilled ON usage_tracking(organization_id, billed) WHERE billed = false;

-- =========================
-- 5) MIGRATE EXISTING DATA - FIXED
-- =========================

-- Create a default organization for existing users
INSERT INTO organizations (name, plan, created_at, updated_at)
SELECT
    'Default Organization',
    'SOLO',
    COALESCE(MIN(b.created_at), now()),  -- FIXED: Use businesses.created_at which exists, or now()
    now()
FROM businesses b
WHERE EXISTS (SELECT 1 FROM users)
  AND NOT EXISTS (SELECT 1 FROM organizations);

-- If no businesses exist, still create a default organization
INSERT INTO organizations (name, plan, created_at, updated_at)
SELECT
    'Default Organization',
    'SOLO',
    now(),
    now()
WHERE EXISTS (SELECT 1 FROM users)
  AND NOT EXISTS (SELECT 1 FROM organizations)
  AND NOT EXISTS (SELECT 1 FROM businesses);

-- Link existing users to the default organization as OWNER
UPDATE users
SET organization_id = (SELECT id FROM organizations ORDER BY id LIMIT 1),
    role = 'OWNER',
    created_at = COALESCE(created_at, now()),  -- Set created_at if null
    updated_at = now()
WHERE organization_id IS NULL
  AND EXISTS (SELECT 1 FROM organizations);

-- Link existing businesses to organizations through users
UPDATE businesses b
SET organization_id = u.organization_id
FROM users u
WHERE b.user_id = u.id
  AND b.organization_id IS NULL;

-- =========================
-- 6) FUNCTIONS FOR USAGE TRACKING
-- =========================

-- Function to get current billing period for an organization
CREATE OR REPLACE FUNCTION get_billing_period_start(org_id BIGINT)
    RETURNS TIMESTAMPTZ AS $$
BEGIN
    RETURN date_trunc('month', now())::timestamptz;
END;
$$ LANGUAGE plpgsql;

-- Function to calculate usage for current billing period
CREATE OR REPLACE FUNCTION calculate_period_usage(
    org_id BIGINT,
    metric_type VARCHAR(50)
)
    RETURNS INTEGER AS $$
DECLARE
    total_usage INTEGER;
BEGIN
    SELECT COALESCE(SUM(quantity), 0)
    INTO total_usage
    FROM usage_tracking
    WHERE organization_id = org_id
      AND metric = metric_type
      AND created_at >= date_trunc('month', now());

    RETURN total_usage;
END;
$$ LANGUAGE plpgsql;

-- =========================
-- 7) TRIGGERS
-- =========================

-- Trigger to update organization updated_at
CREATE TRIGGER organizations_updated_at_trigger
    BEFORE UPDATE ON organizations
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Add trigger for users updated_at
CREATE TRIGGER users_updated_at_trigger
    BEFORE UPDATE ON users
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- =========================
-- 8) COMMENTS FOR DOCUMENTATION
-- =========================

COMMENT ON TABLE organizations IS 'Multi-tenant organization (workspace) management';
COMMENT ON TABLE usage_tracking IS 'Tracks billable usage events for organizations';
COMMENT ON COLUMN organizations.plan IS 'Subscription plan: SOLO, PRO, or GROWTH';
COMMENT ON COLUMN usage_tracking.metric IS 'Type of usage: EMAIL_SENT, SMS_SENT, etc.';
COMMENT ON COLUMN usage_tracking.billed IS 'Whether this usage has been reported to Stripe';

-- =========================
-- 9) USAGE METRICS REFERENCE
-- =========================
CREATE TABLE IF NOT EXISTS usage_metrics_reference (
                                                       code            VARCHAR(50) PRIMARY KEY,
                                                       display_name    VARCHAR(100) NOT NULL,
                                                       default_cost_cents INTEGER DEFAULT 0,
                                                       description     TEXT
);

-- Insert standard metrics
INSERT INTO usage_metrics_reference (code, display_name, default_cost_cents, description) VALUES
                                                                                              ('EMAIL_SENT', 'Email Sent', 0, 'Transactional email sent via SendGrid'),
                                                                                              ('SMS_SENT', 'SMS Message Sent', 15, 'SMS message sent via Twilio'),
                                                                                              ('REVIEW_REQUEST_SENT', 'Review Request Sent', 0, 'Review request sent to customer'),
                                                                                              ('REVIEW_OPENED', 'Review Request Opened', 0, 'Customer opened review request'),
                                                                                              ('REVIEW_COMPLETED', 'Review Completed', 0, 'Customer completed review'),
                                                                                              ('GOOGLE_IMPORT', 'Google Review Imported', 0, 'Review imported from Google'),
                                                                                              ('FACEBOOK_IMPORT', 'Facebook Review Imported', 0, 'Review imported from Facebook'),
                                                                                              ('QR_GENERATED', 'QR Code Generated', 0, 'QR code generated for business'),
                                                                                              ('QR_SCANNED', 'QR Code Scanned', 0, 'QR code scanned by customer'),
                                                                                              ('WIDGET_IMPRESSION', 'Widget Impression', 0, 'Review widget displayed'),
                                                                                              ('WIDGET_INTERACTION', 'Widget Interaction', 0, 'User interacted with widget')
ON CONFLICT (code) DO NOTHING;

-- =========================
-- 10) VIEWS FOR ANALYTICS
-- =========================

-- Organization usage summary view
CREATE OR REPLACE VIEW organization_usage_summary AS
SELECT
    o.id as organization_id,
    o.name as organization_name,
    o.plan,
    DATE_TRUNC('month', ut.created_at) as month,
    ut.metric,
    SUM(ut.quantity) as total_quantity,
    SUM(ut.cost_cents) as total_cost_cents,
    COUNT(*) as event_count
FROM organizations o
         LEFT JOIN usage_tracking ut ON o.id = ut.organization_id
GROUP BY o.id, o.name, o.plan, DATE_TRUNC('month', ut.created_at), ut.metric
ORDER BY o.id, month DESC, ut.metric;

-- Active organizations view
CREATE OR REPLACE VIEW active_organizations AS
SELECT
    o.*,
    COUNT(DISTINCT u.id) as user_count,
    COUNT(DISTINCT b.id) as business_count
FROM organizations o
         LEFT JOIN users u ON o.id = u.organization_id
         LEFT JOIN businesses b ON o.id = b.organization_id
WHERE o.is_active = true
GROUP BY o.id;