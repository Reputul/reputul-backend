ALTER TABLE businesses
    ADD COLUMN logo_filename VARCHAR(255),
    ADD COLUMN logo_url VARCHAR(500),
    ADD COLUMN logo_content_type VARCHAR(100),
    ADD COLUMN logo_uploaded_at TIMESTAMP WITH TIME ZONE;

-- Add index for faster lookups
CREATE INDEX idx_business_logo ON businesses(logo_url);