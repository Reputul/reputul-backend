-- Add beta tester tracking to organizations

-- Add beta tester fields to organizations table
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS beta_tester BOOLEAN DEFAULT false;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS beta_expires_at TIMESTAMPTZ;

-- Add index for querying active beta testers
CREATE INDEX IF NOT EXISTS idx_organizations_beta_tester ON organizations(beta_tester) WHERE beta_tester = true;
CREATE INDEX IF NOT EXISTS idx_organizations_beta_expires ON organizations(beta_expires_at) WHERE beta_expires_at IS NOT NULL;

-- Add SMS limits to organizations (included in plan, not metered)
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS sms_limit_monthly INTEGER;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS sms_used_this_month INTEGER DEFAULT 0;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS sms_period_start TIMESTAMPTZ;

-- Add review request limits
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS review_requests_used_this_month INTEGER DEFAULT 0;

-- Update existing organizations to have default SMS limits based on their plan
-- Solo: 100 SMS/month, Pro: 300 SMS/month, Growth: 1000 SMS/month
UPDATE organizations
SET sms_limit_monthly = CASE
                            WHEN plan = 'SOLO' THEN 100
                            WHEN plan = 'PRO' THEN 300
                            WHEN plan = 'GROWTH' THEN 1000
                            ELSE 100
    END
WHERE sms_limit_monthly IS NULL;

-- Set SMS period start to beginning of current month for existing orgs
UPDATE organizations
SET sms_period_start = date_trunc('month', CURRENT_TIMESTAMP)
WHERE sms_period_start IS NULL;

-- Add helpful comments
COMMENT ON COLUMN organizations.beta_tester IS 'Flag indicating if this organization is a beta tester with extended free access';
COMMENT ON COLUMN organizations.beta_expires_at IS 'When the beta tester access expires (typically 90 days from signup)';
COMMENT ON COLUMN organizations.sms_limit_monthly IS 'Maximum SMS messages allowed per month based on plan';
COMMENT ON COLUMN organizations.sms_used_this_month IS 'Number of SMS messages used in current billing period';
COMMENT ON COLUMN organizations.sms_period_start IS 'Start of current SMS billing period (resets monthly)';
COMMENT ON COLUMN organizations.review_requests_used_this_month IS 'Number of review requests sent in current billing period';

-- Create a view for active beta testers
CREATE OR REPLACE VIEW active_beta_testers AS
SELECT
    o.*,
    COUNT(u.id) as user_count,
    COUNT(b.id) as business_count
FROM organizations o
         LEFT JOIN users u ON u.organization_id = o.id
         LEFT JOIN businesses b ON b.organization_id = o.id
WHERE o.beta_tester = true
  AND (o.beta_expires_at IS NULL OR o.beta_expires_at > CURRENT_TIMESTAMP)
GROUP BY o.id;

COMMENT ON VIEW active_beta_testers IS 'Organizations with active beta tester status';