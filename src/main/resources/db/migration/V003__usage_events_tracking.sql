-- Flyway migration: V003__usage_events_tracking.sql
-- Create usage events table for billing integration and analytics
-- FIXED: Changed 'type' references to 'usage_type' to match V001 schema

-- The usage_events table already exists from V001, so we'll add missing columns and indexes
-- Add any missing columns to existing usage_events table if they don't exist
DO $$
    DECLARE
        column_exists boolean;
    BEGIN
        -- Add quantity column if missing (V001 doesn't have this)
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'usage_events' AND column_name = 'quantity'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE usage_events ADD COLUMN quantity INTEGER NOT NULL DEFAULT 1;
        END IF;

        -- Add reference_id column if missing (V001 has request_id, but we might want reference_id too)
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'usage_events' AND column_name = 'reference_id'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE usage_events ADD COLUMN reference_id VARCHAR(255);
        END IF;

        -- Ensure overage_billed exists (it should from V001)
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'usage_events' AND column_name = 'overage_billed'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE usage_events ADD COLUMN overage_billed BOOLEAN NOT NULL DEFAULT FALSE;
        END IF;
    END $$;

-- Create indexes for performance (using correct column name 'usage_type' not 'type')
CREATE INDEX IF NOT EXISTS idx_usage_events_business_id ON usage_events(business_id);
CREATE INDEX IF NOT EXISTS idx_usage_events_usage_type ON usage_events(usage_type); -- FIXED: was 'type'
CREATE INDEX IF NOT EXISTS idx_usage_events_created_at ON usage_events(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_events_reference_id ON usage_events(reference_id);
CREATE INDEX IF NOT EXISTS idx_usage_events_overage_billed ON usage_events(overage_billed);
CREATE INDEX IF NOT EXISTS idx_usage_events_stripe_usage_record_id ON usage_events(stripe_usage_record_id);

-- Composite indexes for common queries (using correct column name 'usage_type')
CREATE INDEX IF NOT EXISTS idx_usage_events_business_usage_type ON usage_events(business_id, usage_type); -- FIXED: was 'type'
CREATE INDEX IF NOT EXISTS idx_usage_events_business_created_at ON usage_events(business_id, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_events_usage_type_created_at ON usage_events(usage_type, created_at); -- FIXED: was 'type'
CREATE INDEX IF NOT EXISTS idx_usage_events_business_usage_type_created_at ON usage_events(business_id, usage_type, created_at); -- FIXED: was 'type'

-- Index for billing queries (using correct column name 'usage_type')
CREATE INDEX IF NOT EXISTS idx_usage_events_billing_lookup ON usage_events(business_id, usage_type, overage_billed, created_at); -- FIXED: was 'type'

-- Partial index for overage events only (for billing reconciliation)
CREATE INDEX IF NOT EXISTS idx_usage_events_overage_only ON usage_events(business_id, created_at, stripe_usage_record_id)
    WHERE overage_billed = TRUE;

-- Add constraint to ensure positive quantity (only if column exists)
DO $$
    BEGIN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'usage_events' AND column_name = 'quantity'
        ) THEN
            -- Drop existing constraint if it exists
            IF EXISTS (
                SELECT 1 FROM information_schema.check_constraints
                WHERE constraint_name = 'check_positive_quantity'
            ) THEN
                ALTER TABLE usage_events DROP CONSTRAINT check_positive_quantity;
            END IF;

            -- Add the constraint
            ALTER TABLE usage_events ADD CONSTRAINT check_positive_quantity CHECK (quantity > 0);
        END IF;
    END $$;

-- Add helpful comments
COMMENT ON TABLE usage_events IS 'Tracks usage events for billing integration and analytics';
COMMENT ON COLUMN usage_events.usage_type IS 'Type of usage event - determines billing behavior'; -- FIXED: was 'type'
COMMENT ON COLUMN usage_events.request_id IS 'Unique request ID for idempotency';
COMMENT ON COLUMN usage_events.overage_billed IS 'Whether this event was billed as overage to Stripe';
COMMENT ON COLUMN usage_events.stripe_usage_record_id IS 'Stripe usage record ID if billed';
COMMENT ON COLUMN usage_events.metadata IS 'Additional context as JSON';

-- Update the billable events summary view to use correct column name
CREATE OR REPLACE VIEW billable_events_summary AS
SELECT
    b.id as business_id,
    b.name as business_name,
    u.email as user_email,
    ue.usage_type as usage_type, -- FIXED: was 'type'
    DATE_TRUNC('month', ue.created_at) as month,
    COUNT(*) as event_count,
    COALESCE(SUM(ue.quantity), COUNT(*)) as total_quantity, -- Handle cases where quantity might be null
    COUNT(CASE WHEN ue.overage_billed = TRUE THEN 1 END) as billed_count,
    COUNT(CASE WHEN ue.overage_billed = FALSE THEN 1 END) as unbilled_count
FROM usage_events ue
         JOIN businesses b ON ue.business_id = b.id
         JOIN users u ON b.user_id = u.id
WHERE ue.usage_type IN ('SMS_REVIEW_REQUEST_SENT') -- FIXED: was 'type', updated enum values to match V001
GROUP BY b.id, b.name, u.email, ue.usage_type, DATE_TRUNC('month', ue.created_at)
ORDER BY b.id, month DESC;

-- Create view for daily usage analytics (using correct column name)
CREATE OR REPLACE VIEW daily_usage_stats AS
SELECT
    business_id,
    DATE(created_at) as usage_date,
    usage_type, -- FIXED: was 'type'
    COUNT(*) as event_count,
    COALESCE(SUM(quantity), COUNT(*)) as total_quantity -- Handle cases where quantity might be null
FROM usage_events
WHERE created_at >= CURRENT_DATE - INTERVAL '90 days'
GROUP BY business_id, DATE(created_at), usage_type -- FIXED: was 'type'
ORDER BY business_id, usage_date DESC, usage_type; -- FIXED: was 'type'

-- Function to get current period usage for a business (using correct column name)
CREATE OR REPLACE FUNCTION get_current_period_usage(
    p_business_id BIGINT,
    p_period_start TIMESTAMPTZ,
    p_period_end TIMESTAMPTZ
) RETURNS TABLE (
                    event_type VARCHAR(50),
                    event_count BIGINT,
                    total_quantity BIGINT,
                    billed_count BIGINT
                ) AS $$
BEGIN
    RETURN QUERY
        SELECT
            ue.usage_type::VARCHAR(50), -- FIXED: was 'type'
            COUNT(*)::BIGINT,
            COALESCE(SUM(ue.quantity), COUNT(*))::BIGINT, -- Handle cases where quantity might be null
            COUNT(CASE WHEN ue.overage_billed = TRUE THEN 1 END)::BIGINT
        FROM usage_events ue
        WHERE ue.business_id = p_business_id
          AND ue.created_at >= p_period_start
          AND ue.created_at < p_period_end
        GROUP BY ue.usage_type -- FIXED: was 'type'
        ORDER BY ue.usage_type; -- FIXED: was 'type'
END;
$$ LANGUAGE plpgsql;

-- Function to check for duplicate events (idempotency) - using existing request_id column
CREATE OR REPLACE FUNCTION check_duplicate_usage_event(
    p_business_id BIGINT,
    p_usage_type VARCHAR(50), -- FIXED: parameter name
    p_request_id VARCHAR(255) -- Using request_id instead of reference_id to match V001
) RETURNS BOOLEAN AS $$
DECLARE
    event_exists BOOLEAN;
BEGIN
    SELECT EXISTS(
        SELECT 1 FROM usage_events
        WHERE business_id = p_business_id
          AND usage_type = p_usage_type -- FIXED: was 'type'
          AND request_id = p_request_id -- FIXED: was 'reference_id'
    ) INTO event_exists;

    RETURN event_exists;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to prevent duplicate events with same request_id (using existing column from V001)
CREATE OR REPLACE FUNCTION prevent_duplicate_usage_events()
    RETURNS TRIGGER AS $$
BEGIN
    -- Check for duplicates using request_id (which should be unique from V001)
    IF NEW.request_id IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM usage_events
            WHERE business_id = NEW.business_id
              AND usage_type = NEW.usage_type -- FIXED: was 'type'
              AND request_id = NEW.request_id
              AND id != COALESCE(NEW.id, 0) -- Avoid self-reference on updates
        ) THEN
            RAISE EXCEPTION 'Duplicate usage event: business_id=%, usage_type=%, request_id=%',
                NEW.business_id, NEW.usage_type, NEW.request_id; -- FIXED: was 'type'
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Drop existing trigger if it exists, then create it
DROP TRIGGER IF EXISTS trigger_prevent_duplicate_usage_events ON usage_events;
CREATE TRIGGER trigger_prevent_duplicate_usage_events
    BEFORE INSERT ON usage_events
    FOR EACH ROW
EXECUTE FUNCTION prevent_duplicate_usage_events();

-- Note: Skipping sample data insertion since this is an existing production-like migration
-- and V001 may have already created sample data