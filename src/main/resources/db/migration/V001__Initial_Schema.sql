-- V001__Initial_Schema.sql
-- Reputul complete database schema matching entity classes

-- ===== Housekeeping: updated_at trigger =====
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END$$;

-- =========================
-- 1) USERS
-- =========================
CREATE TABLE users (
                       id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       name     VARCHAR(255) NOT NULL,
                       email    VARCHAR(320) NOT NULL,
                       password VARCHAR(255) NOT NULL
);

-- Case-insensitive unique email
CREATE UNIQUE INDEX users_email_ci_unique ON users (lower(email));

-- =========================
-- 2) BUSINESSES
-- =========================
CREATE TABLE businesses (
                            id                           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            user_id                      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            name                         VARCHAR(100) NOT NULL,
                            industry                     VARCHAR(50),
                            phone                        VARCHAR(20),
                            website                      VARCHAR(500),
                            address                      TEXT,
                            reputation_score             DOUBLE PRECISION DEFAULT 0.0,
                            badge                        VARCHAR(50),
                            google_place_id              VARCHAR(200),
                            facebook_page_url            VARCHAR(500),
                            yelp_page_url                VARCHAR(500),
                            review_platforms_configured  BOOLEAN NOT NULL DEFAULT false,
                            created_at                   TIMESTAMPTZ DEFAULT now(),
                            updated_at                   TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX idx_businesses_user_id ON businesses(user_id);

CREATE TRIGGER businesses_set_updated_at
    BEFORE UPDATE ON businesses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 3) CONTACTS
-- =========================
CREATE TABLE contacts (
                          id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          business_id    BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                          name           VARCHAR(255) NOT NULL,
                          email          VARCHAR(255),
                          phone          VARCHAR(50),
                          last_job_date  DATE,
                          tags_json      JSONB,
                          sms_consent    BOOLEAN NOT NULL DEFAULT TRUE,
                          email_consent  BOOLEAN NOT NULL DEFAULT TRUE,
                          created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contacts_business_id ON contacts(business_id);
CREATE INDEX idx_contacts_email ON contacts(lower(email));
CREATE INDEX idx_contacts_phone ON contacts(phone);
CREATE INDEX idx_contacts_tags_gin ON contacts USING GIN (tags_json);

-- Ensure a contact isn't duplicated by email or by phone within a business
CREATE UNIQUE INDEX uq_contacts_biz_email ON contacts (business_id, lower(email))
    WHERE email IS NOT NULL;
CREATE UNIQUE INDEX uq_contacts_biz_phone ON contacts (business_id, phone)
    WHERE phone IS NOT NULL;

CREATE TRIGGER contacts_set_updated_at
    BEFORE UPDATE ON contacts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 4) CUSTOMERS
-- =========================
CREATE TABLE customers (
                           id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           name                      VARCHAR(100) NOT NULL,
                           email                     VARCHAR(150) NOT NULL,
                           phone                     VARCHAR(20),
                           service_date              DATE NOT NULL,
                           service_type              VARCHAR(100) NOT NULL,
                           status                    VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
                           notes                     TEXT,
                           last_feedback_date        TIMESTAMPTZ,
                           feedback_count            INTEGER NOT NULL DEFAULT 0,
                           feedback_submitted        BOOLEAN NOT NULL DEFAULT false,

    -- SMS opt-in/out fields matching Customer entity
                           sms_opt_in                BOOLEAN NOT NULL DEFAULT false,
                           sms_opt_in_method         VARCHAR(50), -- Will store enum values as strings
                           sms_opt_in_timestamp      TIMESTAMPTZ,
                           sms_opt_in_source         TEXT,
                           sms_opt_out               BOOLEAN NOT NULL DEFAULT false,
                           sms_opt_out_timestamp     TIMESTAMPTZ,
                           sms_opt_out_method        VARCHAR(50), -- Will store enum values as strings

    -- SMS sending tracking
                           sms_last_sent_timestamp   TIMESTAMPTZ,
                           sms_send_count_today      INTEGER NOT NULL DEFAULT 0,
                           sms_send_date_reset       DATE,

    -- Relationships (matching entity)
                           business_id               BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                           user_id                   BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

                           created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
                           updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customers_business_id ON customers(business_id);
CREATE INDEX idx_customers_user_id ON customers(user_id);
CREATE INDEX idx_customers_service_date ON customers(service_date);
CREATE INDEX idx_customers_sms_last_sent_ts ON customers(sms_last_sent_timestamp DESC);
CREATE INDEX idx_customers_email ON customers(lower(email));

CREATE TRIGGER customers_set_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 5) CUSTOMER_TAGS (for @ElementCollection)
-- =========================
CREATE TABLE customer_tags (
                               customer_id BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                               tag         VARCHAR(50) NOT NULL,
                               PRIMARY KEY (customer_id, tag)
);

-- =========================
-- 6) EMAIL_TEMPLATES (matching entity structure)
-- =========================
CREATE TABLE email_templates (
                                 id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                 name                VARCHAR(255) NOT NULL,
                                 subject             VARCHAR(500) NOT NULL,
                                 body                TEXT NOT NULL, -- Changed from html_content to body
                                 type                VARCHAR(50) NOT NULL, -- Will store TemplateType enum
                                 is_active           BOOLEAN NOT NULL DEFAULT TRUE,
                                 is_default          BOOLEAN NOT NULL DEFAULT FALSE,
                                 available_variables TEXT, -- Changed from available_variables to TEXT
                                 user_id             BIGINT NOT NULL REFERENCES users(id), -- Changed from business_id to user_id
                                 created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_templates_user_id ON email_templates(user_id);
CREATE INDEX idx_email_templates_type ON email_templates(type);

CREATE TRIGGER email_templates_set_updated_at
    BEFORE UPDATE ON email_templates
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 7) REVIEWS (matching entity structure)
-- =========================
CREATE TABLE reviews (
                         id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                         rating           INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
                         comment          TEXT,
                         source           VARCHAR(50) NOT NULL DEFAULT 'manual',
                         customer_name    VARCHAR(255), -- Added for entity
                         customer_email   VARCHAR(255), -- Added for entity
                         customer_id      BIGINT REFERENCES customers(id), -- Added for entity
                         business_id      BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                         created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reviews_business_id ON reviews(business_id);
CREATE INDEX idx_reviews_customer_id ON reviews(customer_id);
CREATE INDEX idx_reviews_source ON reviews(source);
CREATE INDEX idx_reviews_created_at ON reviews(created_at);

-- =========================
-- 8) REVIEW_REQUESTS (matching entity structure)
-- =========================
CREATE TABLE review_requests (
                                 id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                 customer_id        BIGINT NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                                 business_id        BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                                 email_template_id  BIGINT NOT NULL REFERENCES email_templates(id),
                                 delivery_method    VARCHAR(10) NOT NULL DEFAULT 'EMAIL', -- DeliveryMethod enum
                                 recipient_email    VARCHAR(150) NOT NULL,
                                 recipient_phone    VARCHAR(20),
                                 subject            VARCHAR(500) NOT NULL,
                                 email_body         TEXT,
                                 sms_message        TEXT,
                                 review_link        VARCHAR(500) NOT NULL,
                                 status             VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- RequestStatus enum
                                 sent_at            TIMESTAMPTZ,
                                 opened_at          TIMESTAMPTZ,
                                 clicked_at         TIMESTAMPTZ,
                                 reviewed_at        TIMESTAMPTZ,
                                 created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
                                 error_message      TEXT,
                                 sms_message_id     VARCHAR(100),
                                 sms_status         VARCHAR(50),
                                 sms_error_code     VARCHAR(50)
);

CREATE INDEX idx_review_requests_customer_id ON review_requests(customer_id);
CREATE INDEX idx_review_requests_business_id ON review_requests(business_id);
CREATE INDEX idx_review_requests_status ON review_requests(status);
CREATE UNIQUE INDEX uq_review_requests_sms_msgid ON review_requests(sms_message_id)
    WHERE sms_message_id IS NOT NULL;

CREATE TRIGGER review_requests_set_updated_at
    BEFORE UPDATE ON review_requests
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 9) SUBSCRIPTIONS (complete structure matching entity)
-- =========================
CREATE TABLE subscriptions (
                               id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               stripe_customer_id       VARCHAR(100),
                               stripe_subscription_id   VARCHAR(100),
                               stripe_schedule_id       VARCHAR(100),
                               sms_subscription_item_id VARCHAR(100),
                               plan                     VARCHAR(50) NOT NULL DEFAULT 'SOLO', -- PlanType enum
                               status                   VARCHAR(50) NOT NULL DEFAULT 'INACTIVE', -- SubscriptionStatus enum
                               current_period_start     TIMESTAMPTZ,
                               current_period_end       TIMESTAMPTZ,
                               trial_start              TIMESTAMPTZ,
                               trial_end                TIMESTAMPTZ,
                               promo_code               VARCHAR(100),
                               promo_kind               VARCHAR(50), -- PromoKind enum
                               promo_phase              INTEGER,
                               promo_starts_at          TIMESTAMPTZ,
                               promo_ends_at            TIMESTAMPTZ,
                               start_date               TIMESTAMPTZ, -- Legacy field
                               end_date                 TIMESTAMPTZ, -- Legacy field
                               renewal_date             TIMESTAMPTZ, -- Legacy field
                               trial                    BOOLEAN NOT NULL DEFAULT false, -- Legacy field
                               business_id              BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                               created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscriptions_business_id ON subscriptions(business_id);
CREATE INDEX idx_subscriptions_status ON subscriptions(status);
CREATE INDEX idx_subscriptions_stripe_customer ON subscriptions(stripe_customer_id);

CREATE TRIGGER subscriptions_set_updated_at
    BEFORE UPDATE ON subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =========================
-- 10) IMPORT_JOBS
-- =========================
CREATE TABLE import_jobs (
                             id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                             business_id    BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                             user_id        BIGINT NOT NULL REFERENCES users(id),
                             filename       VARCHAR(255),
                             total_rows     INTEGER NOT NULL DEFAULT 0,
                             inserted_count INTEGER NOT NULL DEFAULT 0,
                             updated_count  INTEGER NOT NULL DEFAULT 0,
                             skipped_count  INTEGER NOT NULL DEFAULT 0,
                             error_count    INTEGER NOT NULL DEFAULT 0,
                             status         VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- Status enum
                             error_details  JSON, -- Changed to JSON to match entity
                             created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_jobs_business_id ON import_jobs(business_id);
CREATE INDEX idx_import_jobs_user_id ON import_jobs(user_id);
CREATE INDEX idx_import_jobs_status ON import_jobs(status);

-- =========================
-- 11) NOTIFICATIONS
-- =========================
CREATE TABLE notifications (
                               id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               message    TEXT,
                               type       VARCHAR(50),
                               read       BOOLEAN NOT NULL DEFAULT false,
                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_read ON notifications(read);

-- =========================
-- 12) PASSWORD_RESET_TOKENS
-- =========================
CREATE TABLE password_reset_tokens (
                                       id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                       user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                       token      VARCHAR(255) NOT NULL,
                                       expires_at TIMESTAMPTZ NOT NULL,
                                       used       BOOLEAN NOT NULL DEFAULT false,
                                       created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_password_reset_token ON password_reset_tokens(token);
CREATE INDEX idx_password_reset_user_id ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_expires_at ON password_reset_tokens(expires_at);

-- =========================
-- 13) STRIPE_EVENTS
-- =========================
CREATE TABLE stripe_events (
                               id           VARCHAR(255) PRIMARY KEY,
                               processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================
-- 14) USAGE_EVENTS
-- =========================
CREATE TABLE usage_events (
                              id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                              business_id             BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                              usage_type              VARCHAR(50) NOT NULL, -- UsageType enum
                              occurred_at             TIMESTAMPTZ NOT NULL,
                              request_id              VARCHAR(100) NOT NULL,
                              overage_billed          BOOLEAN NOT NULL DEFAULT false,
                              stripe_usage_record_id  VARCHAR(100),
                              billing_period_start    TIMESTAMPTZ,
                              billing_period_end      TIMESTAMPTZ,
                              metadata                JSON,
                              created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_usage_events_request_id ON usage_events(request_id);
CREATE INDEX idx_usage_business_type_date ON usage_events(business_id, usage_type, occurred_at);
CREATE INDEX idx_usage_billing_period ON usage_events(business_id, billing_period_start, billing_period_end);

-- =========================
-- 15) USAGE_PERIODS
-- =========================
CREATE TABLE usage_periods (
                               id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                               business_id          BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                               period_start         TIMESTAMPTZ NOT NULL,
                               period_end           TIMESTAMPTZ NOT NULL,
                               sms_sent             INTEGER NOT NULL DEFAULT 0,
                               sms_overage          INTEGER NOT NULL DEFAULT 0,
                               email_sent           INTEGER NOT NULL DEFAULT 0,
                               customers_created    INTEGER NOT NULL DEFAULT 0,
                               requests_sent_today  INTEGER NOT NULL DEFAULT 0,
                               last_reset_date      TIMESTAMPTZ,
                               created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_period_business ON usage_periods(business_id, period_start, period_end);

CREATE TRIGGER usage_periods_set_updated_at
    BEFORE UPDATE ON usage_periods
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();