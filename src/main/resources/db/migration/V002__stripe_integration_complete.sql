-- Flyway migration: V006__stripe_integration_complete.sql
-- Complete Stripe integration schema with all required fields and indexes

-- Ensure subscriptions table exists with all Stripe integration fields
CREATE TABLE IF NOT EXISTS subscriptions (
                                             id BIGSERIAL PRIMARY KEY,
                                             business_id BIGINT NOT NULL,

    -- Stripe Integration Fields
                                             stripe_customer_id VARCHAR(255),
                                             stripe_subscription_id VARCHAR(255) UNIQUE,
                                             stripe_schedule_id VARCHAR(255),
                                             sms_subscription_item_id VARCHAR(255),

    -- Plan and Status
                                             plan VARCHAR(20) NOT NULL DEFAULT 'SOLO'
                                                 CHECK (plan IN ('SOLO', 'PRO', 'GROWTH')),
                                             status VARCHAR(30) NOT NULL DEFAULT 'INACTIVE'
                                                 CHECK (status IN ('INACTIVE', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'INCOMPLETE', 'INCOMPLETE_EXPIRED', 'UNPAID')),

    -- Billing Period
                                             current_period_start TIMESTAMPTZ,
                                             current_period_end TIMESTAMPTZ,

    -- Trial Support
                                             trial_start TIMESTAMPTZ,
                                             trial_end TIMESTAMPTZ,

    -- Promo Code Tracking
                                             promo_code VARCHAR(50),
                                             promo_kind VARCHAR(50)
                                                 CHECK (promo_kind IS NULL OR promo_kind IN ('BETA_3_FREE_THEN_50', 'BETA_6_FREE', 'LAUNCH_50_OFF', 'CUSTOM')),
                                             promo_phase INTEGER,
                                             promo_ends_at TIMESTAMPTZ,

    -- Audit Timestamps
                                             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                             updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Add foreign key constraint if it doesn't exist
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_subscriptions_business'
        ) THEN
            ALTER TABLE subscriptions
                ADD CONSTRAINT fk_subscriptions_business
                    FOREIGN KEY (business_id) REFERENCES businesses(id) ON DELETE CASCADE;
        END IF;
    END $$;

-- Create comprehensive indexes for performance
CREATE INDEX IF NOT EXISTS idx_subscriptions_business_id ON subscriptions(business_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_stripe_customer_id ON subscriptions(stripe_customer_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_stripe_subscription_id ON subscriptions(stripe_subscription_id);
CREATE INDEX IF NOT EXISTS idx_subscriptions_status ON subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_subscriptions_plan ON subscriptions(plan);
CREATE INDEX IF NOT EXISTS idx_subscriptions_trial_end ON subscriptions(trial_end);
CREATE INDEX IF NOT EXISTS idx_subscriptions_current_period_end ON subscriptions(current_period_end);
CREATE INDEX IF NOT EXISTS idx_subscriptions_promo_code ON subscriptions(promo_code);
CREATE INDEX IF NOT EXISTS idx_subscriptions_created_at ON subscriptions(created_at);

-- Composite indexes for common queries
CREATE INDEX IF NOT EXISTS idx_subscriptions_status_plan ON subscriptions(status, plan);
CREATE INDEX IF NOT EXISTS idx_subscriptions_business_status ON subscriptions(business_id, status);

-- Ensure businesses table has user_id and proper indexes
DO $$
    BEGIN
        -- Add user_id to businesses if it doesn't exist
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'businesses' AND column_name = 'user_id'
        ) THEN
            ALTER TABLE businesses ADD COLUMN user_id BIGINT NOT NULL;
            ALTER TABLE businesses ADD CONSTRAINT fk_businesses_user
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
        END IF;
    END $$;

-- Create business indexes if they don't exist
CREATE INDEX IF NOT EXISTS idx_businesses_user_id ON businesses(user_id);
CREATE INDEX IF NOT EXISTS idx_businesses_google_place_id ON businesses(google_place_id);
CREATE INDEX IF NOT EXISTS idx_businesses_reputation_score ON businesses(reputation_score);
CREATE INDEX IF NOT EXISTS idx_businesses_created_at ON businesses(created_at);

-- Add missing columns to existing subscriptions table if they don't exist
DO $$
    DECLARE
        column_exists boolean;
    BEGIN
        -- Add stripe_customer_id if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'stripe_customer_id'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN stripe_customer_id VARCHAR(255);
            CREATE INDEX idx_subscriptions_stripe_customer_id ON subscriptions(stripe_customer_id);
        END IF;

        -- Add stripe_subscription_id if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'stripe_subscription_id'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN stripe_subscription_id VARCHAR(255) UNIQUE;
            CREATE INDEX idx_subscriptions_stripe_subscription_id ON subscriptions(stripe_subscription_id);
        END IF;

        -- Add stripe_schedule_id if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'stripe_schedule_id'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN stripe_schedule_id VARCHAR(255);
        END IF;

        -- Add sms_subscription_item_id if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'sms_subscription_item_id'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN sms_subscription_item_id VARCHAR(255);
        END IF;

        -- Add promo fields if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'promo_code'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN promo_code VARCHAR(50);
            ALTER TABLE subscriptions ADD COLUMN promo_kind VARCHAR(50);
            ALTER TABLE subscriptions ADD COLUMN promo_phase INTEGER;
            ALTER TABLE subscriptions ADD COLUMN promo_ends_at TIMESTAMPTZ;

            -- Add constraint for promo_kind
            ALTER TABLE subscriptions ADD CONSTRAINT check_promo_kind
                CHECK (promo_kind IS NULL OR promo_kind IN ('BETA_3_FREE_THEN_50', 'BETA_6_FREE', 'LAUNCH_50_OFF', 'CUSTOM'));

            CREATE INDEX idx_subscriptions_promo_code ON subscriptions(promo_code);
        END IF;

        -- Add trial fields if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'trial_start'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN trial_start TIMESTAMPTZ;
            ALTER TABLE subscriptions ADD COLUMN trial_end TIMESTAMPTZ;
            CREATE INDEX idx_subscriptions_trial_end ON subscriptions(trial_end);
        END IF;

        -- Add period fields if missing
        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'subscriptions' AND column_name = 'current_period_start'
        ) INTO column_exists;

        IF NOT column_exists THEN
            ALTER TABLE subscriptions ADD COLUMN current_period_start TIMESTAMPTZ;
            ALTER TABLE subscriptions ADD COLUMN current_period_end TIMESTAMPTZ;
            CREATE INDEX idx_subscriptions_current_period_end ON subscriptions(current_period_end);
        END IF;
    END $$;

-- Create or replace function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Create trigger for updated_at if it doesn't exist
DROP TRIGGER IF EXISTS update_subscriptions_updated_at ON subscriptions;
CREATE TRIGGER update_subscriptions_updated_at
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW
EXECUTE FUNCTION update_updated_at_column();

-- Add constraints for data integrity
DO $$
    BEGIN
        -- Ensure plan values are valid
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.check_constraints
            WHERE constraint_name = 'check_subscription_plan'
        ) THEN
            ALTER TABLE subscriptions ADD CONSTRAINT check_subscription_plan
                CHECK (plan IN ('SOLO', 'PRO', 'GROWTH'));
        END IF;

        -- Ensure status values are valid
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.check_constraints
            WHERE constraint_name = 'check_subscription_status'
        ) THEN
            ALTER TABLE subscriptions ADD CONSTRAINT check_subscription_status
                CHECK (status IN ('INACTIVE', 'TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'INCOMPLETE', 'INCOMPLETE_EXPIRED', 'UNPAID'));
        END IF;

        -- Ensure trial_end is after trial_start
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.check_constraints
            WHERE constraint_name = 'check_trial_dates'
        ) THEN
            ALTER TABLE subscriptions ADD CONSTRAINT check_trial_dates
                CHECK (trial_start IS NULL OR trial_end IS NULL OR trial_end > trial_start);
        END IF;

        -- Ensure current_period_end is after current_period_start
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.check_constraints
            WHERE constraint_name = 'check_period_dates'
        ) THEN
            ALTER TABLE subscriptions ADD CONSTRAINT check_period_dates
                CHECK (current_period_start IS NULL OR current_period_end IS NULL OR current_period_end > current_period_start);
        END IF;
    END $$;

-- Create helpful views for common queries
CREATE OR REPLACE VIEW active_subscriptions AS
SELECT
    s.*,
    b.name as business_name,
    u.email as user_email,
    u.name as user_name
FROM subscriptions s
         JOIN businesses b ON s.business_id = b.id
         JOIN users u ON b.user_id = u.id
WHERE s.status IN ('ACTIVE', 'TRIALING');

CREATE OR REPLACE VIEW subscription_summary AS
SELECT
    s.plan,
    s.status,
    COUNT(*) as subscription_count,
    COUNT(CASE WHEN s.status = 'TRIALING' THEN 1 END) as trialing_count,
    COUNT(CASE WHEN s.status = 'ACTIVE' THEN 1 END) as active_count,
    COUNT(CASE WHEN s.promo_code IS NOT NULL THEN 1 END) as promo_count
FROM subscriptions s
WHERE s.status IN ('ACTIVE', 'TRIALING', 'PAST_DUE')
GROUP BY s.plan, s.status;

-- Add helpful comments to the table
COMMENT ON TABLE subscriptions IS 'Customer subscriptions with full Stripe integration support';
COMMENT ON COLUMN subscriptions.stripe_customer_id IS 'Stripe customer ID for billing';
COMMENT ON COLUMN subscriptions.stripe_subscription_id IS 'Stripe subscription ID - unique identifier in Stripe';
COMMENT ON COLUMN subscriptions.sms_subscription_item_id IS 'Stripe subscription item ID for metered SMS billing';
COMMENT ON COLUMN subscriptions.promo_code IS 'Promotional code applied to subscription';
COMMENT ON COLUMN subscriptions.promo_phase IS '1=free phase, 2=discount phase for multi-phase promos';

-- Insert some test data for development (only if no subscriptions exist)
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM subscriptions LIMIT 1) AND
           EXISTS (SELECT 1 FROM businesses LIMIT 1) THEN

            INSERT INTO subscriptions (
                business_id,
                plan,
                status,
                created_at,
                updated_at
            )
            SELECT
                b.id,
                'SOLO',
                'INACTIVE',
                NOW(),
                NOW()
            FROM businesses b
            LIMIT 3; -- Add inactive subscriptions for first 3 businesses

            -- Log that test data was inserted
            RAISE NOTICE 'Inserted test subscription records for development';
        END IF;
    END $$;