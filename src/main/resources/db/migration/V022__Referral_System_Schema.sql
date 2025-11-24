-- V022__Referral_System_Schema.sql
-- Comprehensive Referral System for Reputul

-- =========================
-- 1) REFERRAL PROGRAMS
-- =========================

CREATE TABLE referral_programs (
                                   id                  BIGSERIAL PRIMARY KEY,
                                   organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                   business_id         BIGINT REFERENCES businesses(id) ON DELETE CASCADE, -- NULL = org-wide program

    -- Program Configuration
                                   name                VARCHAR(100) NOT NULL,
                                   description         TEXT,
                                   is_active           BOOLEAN DEFAULT true,

    -- Referral Settings
                                   reward_type         VARCHAR(20) NOT NULL DEFAULT 'DISCOUNT', -- 'DISCOUNT', 'CASH', 'CREDIT', 'GIFT_CARD', 'SERVICE'
                                   reward_amount       DECIMAL(10,2), -- Dollar amount for cash/credit/gift cards
                                   reward_percentage   INTEGER, -- Percentage for discounts (5 = 5%)
                                   reward_description  VARCHAR(255), -- "10% off next service", "$25 gift card", etc.

    -- Referrer Rewards (person making the referral)
                                   referrer_reward_type        VARCHAR(20) DEFAULT 'DISCOUNT',
                                   referrer_reward_amount      DECIMAL(10,2),
                                   referrer_reward_percentage  INTEGER,
                                   referrer_reward_description VARCHAR(255),

    -- Program Limits
                                   max_referrals_per_customer  INTEGER, -- NULL = unlimited
                                   max_program_redemptions     INTEGER, -- NULL = unlimited
                                   min_purchase_amount         DECIMAL(10,2), -- Minimum spend to qualify
                                   expires_at                  TIMESTAMP,

    -- Tracking
                                   total_referrals     INTEGER DEFAULT 0,
                                   total_conversions   INTEGER DEFAULT 0,
                                   total_revenue       DECIMAL(10,2) DEFAULT 0,

                                   created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                   updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 2) REFERRAL LINKS/CODES
-- =========================

CREATE TABLE referral_links (
                                id                  BIGSERIAL PRIMARY KEY,
                                organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                business_id         BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                                referral_program_id BIGINT NOT NULL REFERENCES referral_programs(id) ON DELETE CASCADE,
                                customer_id         BIGINT REFERENCES customers(id) ON DELETE CASCADE,

    -- Link Details
                                referral_code       VARCHAR(20) UNIQUE NOT NULL, -- "JOHN2024", "SMITH10", etc.
                                referral_url        TEXT NOT NULL, -- Full tracking URL
                                short_url           VARCHAR(50), -- Shortened version for SMS

    -- Referrer Information (who's making the referral)
                                referrer_name       VARCHAR(100),
                                referrer_email      VARCHAR(255),
                                referrer_phone      VARCHAR(20),

    -- Tracking
                                clicks              INTEGER DEFAULT 0,
                                conversions         INTEGER DEFAULT 0,
                                total_revenue       DECIMAL(10,2) DEFAULT 0,

    -- Status
                                is_active           BOOLEAN DEFAULT true,
                                expires_at          TIMESTAMP,

                                created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                last_used_at        TIMESTAMP
);

-- =========================
-- 3) REFERRAL TRACKING
-- =========================

CREATE TABLE referrals (
                           id                  BIGSERIAL PRIMARY KEY,
                           organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                           business_id         BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                           referral_program_id BIGINT NOT NULL REFERENCES referral_programs(id) ON DELETE CASCADE,
                           referral_link_id    BIGINT NOT NULL REFERENCES referral_links(id) ON DELETE CASCADE,

    -- Referrer (person who made the referral)
                           referrer_customer_id BIGINT REFERENCES customers(id),
                           referrer_name        VARCHAR(100),
                           referrer_email       VARCHAR(255),

    -- Referee (person who was referred)
                           referee_name         VARCHAR(100),
                           referee_email        VARCHAR(255),
                           referee_phone        VARCHAR(20),
                           referee_customer_id  BIGINT REFERENCES customers(id), -- Set when they become a customer

    -- Tracking Details
                           referral_code        VARCHAR(20),
                           click_timestamp      TIMESTAMP,
                           ip_address           VARCHAR(45),
                           user_agent           TEXT,
                           utm_source           VARCHAR(100),
                           utm_campaign         VARCHAR(100),

    -- Conversion Tracking
                           status               VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'CONVERTED', 'REWARDED', 'EXPIRED', 'CANCELLED'
                           converted_at         TIMESTAMP,
                           purchase_amount      DECIMAL(10,2),
                           reward_issued_at     TIMESTAMP,
                           reward_claimed_at    TIMESTAMP,

    -- Metadata
                           metadata             JSONB, -- Additional tracking data

                           created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                           updated_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 4) REFERRAL REWARDS
-- =========================

CREATE TABLE referral_rewards (
                                  id                  BIGSERIAL PRIMARY KEY,
                                  organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                  referral_id         BIGINT NOT NULL REFERENCES referrals(id) ON DELETE CASCADE,
                                  customer_id         BIGINT REFERENCES customers(id),

    -- Reward Details
                                  reward_type         VARCHAR(20) NOT NULL, -- 'REFERRER', 'REFEREE'
                                  amount              DECIMAL(10,2),
                                  percentage          INTEGER,
                                  description         VARCHAR(255),

    -- Reward Status
                                  status              VARCHAR(20) DEFAULT 'PENDING', -- 'PENDING', 'ISSUED', 'CLAIMED', 'EXPIRED', 'CANCELLED'
                                  issued_at           TIMESTAMP,
                                  claimed_at          TIMESTAMP,
                                  expires_at          TIMESTAMP,

    -- Reward Details
                                  reward_code         VARCHAR(50), -- Discount code or voucher number
                                  reward_instructions TEXT, -- How to claim/use the reward

                                  created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                  updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 5) REFERRAL CAMPAIGNS (Automation Integration)
-- =========================

CREATE TABLE referral_campaigns (
                                    id                      BIGSERIAL PRIMARY KEY,
                                    organization_id         BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                    business_id             BIGINT REFERENCES businesses(id) ON DELETE CASCADE,
                                    referral_program_id     BIGINT NOT NULL REFERENCES referral_programs(id) ON DELETE CASCADE,

    -- Campaign Configuration
                                    name                    VARCHAR(100) NOT NULL,
                                    description             TEXT,
                                    is_active               BOOLEAN DEFAULT true,

    -- Trigger Configuration (when to send referral invites)
                                    trigger_type            VARCHAR(30) NOT NULL DEFAULT 'REVIEW_SUBMITTED', -- 'REVIEW_SUBMITTED', 'SERVICE_COMPLETED', 'POSITIVE_FEEDBACK', 'MANUAL'
                                    trigger_conditions      JSONB, -- {"min_rating": 4, "review_platforms": ["google", "facebook"]}

    -- Campaign Settings
                                    delay_hours             INTEGER DEFAULT 24, -- Wait time before sending referral invite
                                    max_sends_per_customer  INTEGER DEFAULT 3, -- Don't spam customers
                                    send_method             VARCHAR(20) DEFAULT 'EMAIL', -- 'EMAIL', 'SMS', 'BOTH'

    -- Email Template
                                    email_subject           VARCHAR(200),
                                    email_template          TEXT,

    -- SMS Template
                                    sms_template            TEXT,

    -- Performance Tracking
                                    total_sent              INTEGER DEFAULT 0,
                                    total_clicks            INTEGER DEFAULT 0,
                                    total_conversions       INTEGER DEFAULT 0,

                                    created_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                    updated_at              TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 6) REFERRAL AUTOMATION TRACKING
-- =========================

CREATE TABLE referral_automations (
                                      id                  BIGSERIAL PRIMARY KEY,
                                      organization_id     BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                      referral_campaign_id BIGINT NOT NULL REFERENCES referral_campaigns(id) ON DELETE CASCADE,
                                      customer_id         BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,

    -- Execution Details
                                      trigger_event       VARCHAR(50), -- 'REVIEW_SUBMITTED', 'HIGH_RATING_RECEIVED', etc.
                                      trigger_data        JSONB, -- Details about what triggered this

    -- Scheduling
                                      scheduled_send_at   TIMESTAMP,
                                      sent_at             TIMESTAMP,

    -- Message Details
                                      delivery_method     VARCHAR(20), -- 'EMAIL', 'SMS'
                                      message_content     TEXT,
                                      referral_link_id    BIGINT REFERENCES referral_links(id),

    -- Tracking
                                      status              VARCHAR(20) DEFAULT 'SCHEDULED', -- 'SCHEDULED', 'SENT', 'DELIVERED', 'OPENED', 'CLICKED', 'FAILED'
                                      delivery_status     JSONB, -- Provider-specific delivery details
                                      opened_at           TIMESTAMP,
                                      clicked_at          TIMESTAMP,

                                      created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                      updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- =========================
-- 7) INDEXES FOR PERFORMANCE
-- =========================

-- Organization-scoped queries
CREATE INDEX idx_referral_programs_organization ON referral_programs(organization_id, is_active);
CREATE INDEX idx_referral_links_organization ON referral_links(organization_id, is_active);
CREATE INDEX idx_referrals_organization ON referrals(organization_id, status);
CREATE INDEX idx_referral_rewards_organization ON referral_rewards(organization_id, status);
CREATE INDEX idx_referral_campaigns_organization ON referral_campaigns(organization_id, is_active);

-- Business-scoped queries
CREATE INDEX idx_referral_programs_business ON referral_programs(business_id, is_active);
CREATE INDEX idx_referral_links_business ON referral_links(business_id, is_active);
CREATE INDEX idx_referrals_business ON referrals(business_id, status);

-- Referral link lookups
CREATE INDEX idx_referral_links_code ON referral_links(referral_code);
CREATE INDEX idx_referral_links_url ON referral_links(referral_url);

-- Customer tracking
CREATE INDEX idx_referrals_referrer ON referrals(referrer_customer_id);
CREATE INDEX idx_referrals_referee ON referrals(referee_customer_id);
CREATE INDEX idx_referral_rewards_customer ON referral_rewards(customer_id, status);

-- Time-based queries
CREATE INDEX idx_referrals_created_at ON referrals(created_at);
CREATE INDEX idx_referral_automations_scheduled ON referral_automations(scheduled_send_at, status);

-- =========================
-- 8) DEFAULT DATA - SAMPLE REFERRAL PROGRAMS
-- =========================

-- Insert default referral program templates
INSERT INTO referral_programs (
    organization_id, name, description,
    reward_type, reward_percentage, reward_description,
    referrer_reward_type, referrer_reward_percentage, referrer_reward_description,
    max_referrals_per_customer
) VALUES
-- This is a template - actual programs will be created per organization
(
    1, -- Will be updated to actual organization_id when created
    'Friend Referral Discount',
    'Get 10% off when you refer a friend, and they get 10% off too!',
    'DISCOUNT', 10, '10% off your next service',
    'DISCOUNT', 10, '10% off for referring a friend',
    NULL -- unlimited referrals
);

-- =========================
-- 9) TRIGGERS FOR AUTOMATED UPDATES
-- =========================

-- Update referral_programs totals when referrals change
CREATE OR REPLACE FUNCTION update_referral_program_stats()
    RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        -- Update program stats
        UPDATE referral_programs SET
                                     total_referrals = (
                                         SELECT COUNT(*) FROM referrals
                                         WHERE referral_program_id = NEW.referral_program_id
                                     ),
                                     total_conversions = (
                                         SELECT COUNT(*) FROM referrals
                                         WHERE referral_program_id = NEW.referral_program_id
                                           AND status = 'CONVERTED'
                                     ),
                                     total_revenue = (
                                         SELECT COALESCE(SUM(purchase_amount), 0) FROM referrals
                                         WHERE referral_program_id = NEW.referral_program_id
                                           AND status = 'CONVERTED'
                                     ),
                                     updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.referral_program_id;

        -- Update referral link stats
        UPDATE referral_links SET
                                  conversions = (
                                      SELECT COUNT(*) FROM referrals
                                      WHERE referral_link_id = NEW.referral_link_id
                                        AND status = 'CONVERTED'
                                  ),
                                  total_revenue = (
                                      SELECT COALESCE(SUM(purchase_amount), 0) FROM referrals
                                      WHERE referral_link_id = NEW.referral_link_id
                                        AND status = 'CONVERTED'
                                  )
        WHERE id = NEW.referral_link_id;

        RETURN NEW;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER referral_stats_update_trigger
    AFTER INSERT OR UPDATE ON referrals
    FOR EACH ROW
EXECUTE FUNCTION update_referral_program_stats();

-- =========================
-- 10) COMMENTS FOR DOCUMENTATION
-- =========================

COMMENT ON TABLE referral_programs IS 'Referral program configurations per organization/business';
COMMENT ON TABLE referral_links IS 'Unique referral links generated for customers to share';
COMMENT ON TABLE referrals IS 'Individual referral tracking - who referred whom and conversion status';
COMMENT ON TABLE referral_rewards IS 'Rewards issued to referrers and referees';
COMMENT ON TABLE referral_campaigns IS 'Automated referral invitation campaigns';
COMMENT ON TABLE referral_automations IS 'Individual automation executions for referral campaigns';

COMMENT ON COLUMN referral_programs.reward_type IS 'Type of reward: DISCOUNT, CASH, CREDIT, GIFT_CARD, SERVICE';
COMMENT ON COLUMN referrals.status IS 'Referral status: PENDING, CONVERTED, REWARDED, EXPIRED, CANCELLED';
COMMENT ON COLUMN referral_rewards.reward_type IS 'Who gets the reward: REFERRER (person making referral) or REFEREE (person being referred)';