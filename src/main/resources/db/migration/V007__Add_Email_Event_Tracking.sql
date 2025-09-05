-- V007__Add_Email_Event_Tracking.sql
-- Add SendGrid email event tracking to review_requests

-- =========================
-- 1) ADD EMAIL TRACKING FIELDS TO REVIEW_REQUESTS
-- =========================
ALTER TABLE review_requests
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sendgrid_message_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS email_status VARCHAR(50),
    ADD COLUMN IF NOT EXISTS email_error_code VARCHAR(255);

-- =========================
-- 2) ADD INDEXES FOR PERFORMANCE
-- =========================

-- Index for webhook lookups
CREATE INDEX IF NOT EXISTS idx_review_requests_sendgrid_message_id
    ON review_requests(sendgrid_message_id)
    WHERE sendgrid_message_id IS NOT NULL;

-- Index for email status queries
CREATE INDEX IF NOT EXISTS idx_review_requests_email_status
    ON review_requests(email_status);

-- Index for delivery tracking
CREATE INDEX IF NOT EXISTS idx_review_requests_delivered_at
    ON review_requests(delivered_at)
    WHERE delivered_at IS NOT NULL;

-- Composite index for analytics queries
CREATE INDEX IF NOT EXISTS idx_review_requests_business_email_status
    ON review_requests(business_id, email_status)
    WHERE delivery_method = 'EMAIL';

-- Index for finding stuck emails
CREATE INDEX IF NOT EXISTS idx_review_requests_sent_no_status
    ON review_requests(sent_at, email_status)
    WHERE delivery_method = 'EMAIL' AND status = 'SENT' AND email_status IS NULL;

-- =========================
-- 3) ADD ENUM TYPE FOR REQUEST STATUS (if not exists)
-- =========================
DO $$
    BEGIN
        -- Check if the enum type exists
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'request_status') THEN
            CREATE TYPE request_status AS ENUM (
                'PENDING',
                'SENT',
                'DELIVERED',
                'OPENED',
                'CLICKED',
                'COMPLETED',
                'FAILED',
                'BOUNCED'
                );
        END IF;
    END $$;

-- =========================
-- 4) UPDATE STATUS VALUES TO INCLUDE NEW STATES
-- =========================
-- Add new status values if using enum (PostgreSQL specific)
DO $$
    BEGIN
        -- Add DELIVERED status if not exists
        IF NOT EXISTS (
            SELECT 1 FROM pg_enum
            WHERE enumlabel = 'DELIVERED'
              AND enumtypid = (SELECT oid FROM pg_type WHERE typname = 'request_status')
        ) THEN
            ALTER TYPE request_status ADD VALUE IF NOT EXISTS 'DELIVERED' AFTER 'SENT';
        END IF;

        -- Add CLICKED status if not exists
        IF NOT EXISTS (
            SELECT 1 FROM pg_enum
            WHERE enumlabel = 'CLICKED'
              AND enumtypid = (SELECT oid FROM pg_type WHERE typname = 'request_status')
        ) THEN
            ALTER TYPE request_status ADD VALUE IF NOT EXISTS 'CLICKED' AFTER 'OPENED';
        END IF;

        -- Add BOUNCED status if not exists
        IF NOT EXISTS (
            SELECT 1 FROM pg_enum
            WHERE enumlabel = 'BOUNCED'
              AND enumtypid = (SELECT oid FROM pg_type WHERE typname = 'request_status')
        ) THEN
            ALTER TYPE request_status ADD VALUE IF NOT EXISTS 'BOUNCED' AFTER 'FAILED';
        END IF;
    EXCEPTION
        WHEN undefined_object THEN
            -- If enum doesn't exist, do nothing (using VARCHAR)
            NULL;
    END $$;

-- =========================
-- 5) EMAIL STATUS REFERENCE TABLE
-- =========================
CREATE TABLE IF NOT EXISTS email_status_reference (
                                                      status VARCHAR(50) PRIMARY KEY,
                                                      description TEXT,
                                                      is_success BOOLEAN,
                                                      is_final BOOLEAN
);

-- Insert standard SendGrid event types
INSERT INTO email_status_reference (status, description, is_success, is_final) VALUES
                                                                                   ('sent', 'Email sent to SendGrid', true, false),
                                                                                   ('processed', 'SendGrid processed the email', true, false),
                                                                                   ('delivered', 'Email delivered to recipient server', true, false),
                                                                                   ('opened', 'Recipient opened the email', true, false),
                                                                                   ('clicked', 'Recipient clicked a link', true, true),
                                                                                   ('bounce', 'Email bounced (invalid address)', false, true),
                                                                                   ('dropped', 'Email dropped by SendGrid', false, true),
                                                                                   ('deferred', 'Email temporarily deferred', false, false),
                                                                                   ('spamreport', 'Recipient marked as spam', false, true),
                                                                                   ('unsubscribe', 'Recipient unsubscribed', false, true)
ON CONFLICT (status) DO NOTHING;

-- =========================
-- 6) ANALYTICS VIEW
-- =========================
CREATE OR REPLACE VIEW email_delivery_analytics AS
SELECT
    rr.business_id,
    b.name as business_name,
    DATE(rr.created_at) as date,
    COUNT(*) as total_emails,
    COUNT(CASE WHEN rr.email_status = 'delivered' THEN 1 END) as delivered,
    COUNT(CASE WHEN rr.email_status IN ('opened', 'clicked') THEN 1 END) as opened,
    COUNT(CASE WHEN rr.email_status = 'clicked' THEN 1 END) as clicked,
    COUNT(CASE WHEN rr.email_status IN ('bounce', 'dropped') THEN 1 END) as failed,
    ROUND(COUNT(CASE WHEN rr.email_status = 'delivered' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) as delivery_rate,
    ROUND(COUNT(CASE WHEN rr.email_status IN ('opened', 'clicked') THEN 1 END) * 100.0 / NULLIF(COUNT(CASE WHEN rr.email_status = 'delivered' THEN 1 END), 0), 2) as open_rate,
    ROUND(COUNT(CASE WHEN rr.email_status = 'clicked' THEN 1 END) * 100.0 / NULLIF(COUNT(CASE WHEN rr.email_status IN ('opened', 'clicked') THEN 1 END), 0), 2) as click_rate
FROM review_requests rr
         JOIN businesses b ON rr.business_id = b.id
WHERE rr.delivery_method = 'EMAIL'
GROUP BY rr.business_id, b.name, DATE(rr.created_at);

-- =========================
-- 7) COMBINED DELIVERY ANALYTICS VIEW
-- =========================
CREATE OR REPLACE VIEW delivery_method_analytics AS
SELECT
    rr.business_id,
    b.name as business_name,
    rr.delivery_method,
    DATE(rr.created_at) as date,
    COUNT(*) as total_sent,
    COUNT(CASE WHEN rr.status IN ('DELIVERED', 'OPENED', 'CLICKED', 'COMPLETED') THEN 1 END) as successful,
    COUNT(CASE WHEN rr.status = 'COMPLETED' THEN 1 END) as completed,
    COUNT(CASE WHEN rr.status IN ('FAILED', 'BOUNCED') THEN 1 END) as failed,
    ROUND(COUNT(CASE WHEN rr.status = 'COMPLETED' THEN 1 END) * 100.0 / NULLIF(COUNT(*), 0), 2) as completion_rate
FROM review_requests rr
         JOIN businesses b ON rr.business_id = b.id
WHERE rr.sent_at IS NOT NULL
GROUP BY rr.business_id, b.name, rr.delivery_method, DATE(rr.created_at)
ORDER BY rr.business_id, date DESC, rr.delivery_method;

-- =========================
-- 8) FUNCTION TO UPDATE EMAIL STATUS (MONOTONIC)
-- =========================
CREATE OR REPLACE FUNCTION update_email_status_monotonic(
    message_id VARCHAR(255),
    new_status VARCHAR(50),
    error_code VARCHAR(255) DEFAULT NULL
)
    RETURNS BOOLEAN AS $$
DECLARE
    current_status VARCHAR(50);
    status_hierarchy INTEGER;
    new_status_hierarchy INTEGER;
    review_request_id BIGINT;
BEGIN
    -- Define status hierarchy (higher number = more advanced status)
    CASE new_status
        WHEN 'sent' THEN new_status_hierarchy := 1;
        WHEN 'processed' THEN new_status_hierarchy := 2;
        WHEN 'delivered' THEN new_status_hierarchy := 3;
        WHEN 'deferred' THEN new_status_hierarchy := 3;
        WHEN 'opened' THEN new_status_hierarchy := 4;
        WHEN 'clicked' THEN new_status_hierarchy := 5;
        WHEN 'bounce' THEN new_status_hierarchy := 6;
        WHEN 'dropped' THEN new_status_hierarchy := 6;
        WHEN 'spamreport' THEN new_status_hierarchy := 6;
        WHEN 'unsubscribe' THEN new_status_hierarchy := 6;
        ELSE new_status_hierarchy := 0;
        END CASE;

    -- Get current status and ID
    SELECT email_status, id INTO current_status, review_request_id
    FROM review_requests
    WHERE sendgrid_message_id = message_id;

    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    -- Determine current status hierarchy
    IF current_status IS NULL THEN
        status_hierarchy := 0;
    ELSE
        CASE current_status
            WHEN 'sent' THEN status_hierarchy := 1;
            WHEN 'processed' THEN status_hierarchy := 2;
            WHEN 'delivered' THEN status_hierarchy := 3;
            WHEN 'deferred' THEN status_hierarchy := 3;
            WHEN 'opened' THEN status_hierarchy := 4;
            WHEN 'clicked' THEN status_hierarchy := 5;
            WHEN 'bounce' THEN status_hierarchy := 6;
            WHEN 'dropped' THEN status_hierarchy := 6;
            WHEN 'spamreport' THEN status_hierarchy := 6;
            WHEN 'unsubscribe' THEN status_hierarchy := 6;
            ELSE status_hierarchy := 0;
            END CASE;
    END IF;

    -- Only update if new status is higher in hierarchy (monotonic progression)
    IF new_status_hierarchy > status_hierarchy OR
       (new_status IN ('bounce', 'dropped', 'spamreport') AND status_hierarchy < 6) THEN

        UPDATE review_requests
        SET
            email_status = new_status,
            email_error_code = COALESCE(error_code, email_error_code),
            delivered_at = CASE
                               WHEN new_status = 'delivered' THEN NOW()
                               ELSE delivered_at
                END,
            opened_at = CASE
                            WHEN new_status = 'opened' THEN NOW()
                            ELSE opened_at
                END,
            clicked_at = CASE
                             WHEN new_status = 'clicked' THEN NOW()
                             ELSE clicked_at
                END,
            -- Update main status based on email status
            status = CASE
                         WHEN new_status = 'delivered' THEN 'DELIVERED'
                         WHEN new_status = 'opened' THEN 'OPENED'
                         WHEN new_status = 'clicked' THEN 'CLICKED'
                         WHEN new_status = 'bounce' THEN 'BOUNCED'
                         WHEN new_status IN ('dropped', 'spamreport') THEN 'FAILED'
                         ELSE status
                END,
            error_message = CASE
                                WHEN new_status IN ('bounce', 'dropped', 'spamreport')
                                    THEN CONCAT('Email ', new_status, ': ', COALESCE(error_code, 'Unknown error'))
                                ELSE error_message
                END,
            updated_at = NOW()
        WHERE id = review_request_id;

        RETURN TRUE;
    END IF;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql;

-- =========================
-- 9) COMMENTS FOR DOCUMENTATION
-- =========================
COMMENT ON COLUMN review_requests.sendgrid_message_id IS 'SendGrid message ID for webhook event tracking';
COMMENT ON COLUMN review_requests.email_status IS 'SendGrid event status: sent, processed, delivered, opened, clicked, bounce, dropped, deferred';
COMMENT ON COLUMN review_requests.delivered_at IS 'Timestamp when email was delivered to recipient';
COMMENT ON COLUMN review_requests.email_error_code IS 'Error code/reason for bounced or failed emails';

-- =========================
-- 10) MONITORING QUERIES (AS COMMENTS)
-- =========================

/*
-- Check email delivery health:
SELECT email_status, COUNT(*)
FROM review_requests
WHERE delivery_method = 'EMAIL'
AND created_at > NOW() - INTERVAL '24 hours'
GROUP BY email_status;

-- Find stuck emails:
SELECT * FROM review_requests
WHERE delivery_method = 'EMAIL'
AND status = 'SENT'
AND email_status IS NULL
AND sent_at < NOW() - INTERVAL '1 hour';

-- Get delivery metrics by business:
SELECT * FROM email_delivery_analytics
WHERE date >= CURRENT_DATE - INTERVAL '7 days'
ORDER BY business_id, date;

-- Compare email vs SMS performance:
SELECT * FROM delivery_method_analytics
WHERE date >= CURRENT_DATE - INTERVAL '30 days'
ORDER BY business_id, delivery_method, date;
*/