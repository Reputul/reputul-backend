-- Platform integration tables
CREATE TABLE channel_credentials (
                                     id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                     org_id              BIGINT NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
                                     business_id         BIGINT REFERENCES businesses(id) ON DELETE CASCADE,
                                     platform_type       VARCHAR(50) NOT NULL,
                                     status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',

                                     access_token        TEXT,
                                     refresh_token       TEXT,
                                     token_expires_at    TIMESTAMPTZ,

                                     metadata            JSONB,

                                     last_sync_at        TIMESTAMPTZ,
                                     last_sync_status    VARCHAR(20),
                                     sync_error_message  TEXT,
                                     next_sync_scheduled TIMESTAMPTZ,

                                     created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
                                     created_by_user_id  BIGINT REFERENCES users(id),

                                     CONSTRAINT uq_org_business_platform UNIQUE (org_id, business_id, platform_type)
);

CREATE INDEX idx_channel_credentials_org_id ON channel_credentials(org_id);
CREATE INDEX idx_channel_credentials_business_id ON channel_credentials(business_id);
CREATE INDEX idx_channel_credentials_status ON channel_credentials(status);
CREATE INDEX idx_channel_credentials_next_sync ON channel_credentials(next_sync_scheduled)
    WHERE status = 'ACTIVE' AND next_sync_scheduled IS NOT NULL;

-- Review sync tracking table
CREATE TABLE review_sync_jobs (
                                  id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                  credential_id       BIGINT NOT NULL REFERENCES channel_credentials(id) ON DELETE CASCADE,
                                  business_id         BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                                  platform_type       VARCHAR(50) NOT NULL,

                                  status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                  started_at          TIMESTAMPTZ,
                                  completed_at        TIMESTAMPTZ,

                                  reviews_fetched     INTEGER DEFAULT 0,
                                  reviews_new         INTEGER DEFAULT 0,
                                  reviews_updated     INTEGER DEFAULT 0,
                                  reviews_skipped     INTEGER DEFAULT 0,

                                  error_message       TEXT,
                                  error_details       JSONB,
                                  retry_count         INTEGER DEFAULT 0,

                                  created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_sync_jobs_credential_id ON review_sync_jobs(credential_id);
CREATE INDEX idx_review_sync_jobs_status ON review_sync_jobs(status);
CREATE INDEX idx_review_sync_jobs_created_at ON review_sync_jobs(created_at);

-- Extend reviews table with platform-specific metadata
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS source_review_id VARCHAR(255);
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS source_review_url TEXT;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS reviewer_photo_url TEXT;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS platform_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS platform_response TEXT;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS platform_response_at TIMESTAMPTZ;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS synced_at TIMESTAMPTZ;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS source_metadata JSONB;

-- Unique constraint to prevent duplicate reviews from same platform
CREATE UNIQUE INDEX uq_reviews_source_id ON reviews(business_id, source, source_review_id)
    WHERE source_review_id IS NOT NULL;

-- Trigger for updated_at on channel_credentials
CREATE TRIGGER channel_credentials_set_updated_at
    BEFORE UPDATE ON channel_credentials
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();