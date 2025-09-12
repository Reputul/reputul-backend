-- V009__fix_missing_reputation_columns.sql
-- Fix missing columns from V008 that may not have been applied correctly

-- =========================
-- Ensure all reputation columns exist in businesses table
-- =========================
-- These should exist from V008, but adding with IF NOT EXISTS for safety
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS reputul_rating DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS reputation_score_quality DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS reputation_score_velocity DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS reputation_score_responsiveness DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS reputation_score_composite DOUBLE PRECISION DEFAULT 0.0;
ALTER TABLE businesses ADD COLUMN IF NOT EXISTS last_reputation_update TIMESTAMPTZ;

-- =========================
-- Ensure indexes exist
-- =========================
CREATE INDEX IF NOT EXISTS idx_businesses_reputul_rating ON businesses(reputul_rating);
CREATE INDEX IF NOT EXISTS idx_businesses_reputation_composite ON businesses(reputation_score_composite);
CREATE INDEX IF NOT EXISTS idx_businesses_industry ON businesses(industry);

-- =========================
-- Ensure response tracking columns exist in reviews table
-- =========================
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS has_business_response BOOLEAN DEFAULT FALSE;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS business_response_time_hours INTEGER;
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS response_at TIMESTAMPTZ;

-- Add indexes for response tracking
CREATE INDEX IF NOT EXISTS idx_reviews_has_business_response ON reviews(has_business_response);
CREATE INDEX IF NOT EXISTS idx_reviews_response_time ON reviews(business_response_time_hours) WHERE business_response_time_hours IS NOT NULL;

-- =========================
-- Ensure industry_benchmarks table exists
-- =========================
CREATE TABLE IF NOT EXISTS industry_benchmarks (
                                                   id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                   industry VARCHAR(50) NOT NULL UNIQUE,
                                                   median_reviews_per_30d DOUBLE PRECISION NOT NULL DEFAULT 3.0,
                                                   percentile_25_reviews DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                                                   percentile_75_reviews DOUBLE PRECISION NOT NULL DEFAULT 6.0,
                                                   sample_size INTEGER NOT NULL DEFAULT 100,
                                                   last_updated TIMESTAMPTZ DEFAULT now(),
                                                   created_at TIMESTAMPTZ DEFAULT now(),
                                                   updated_at TIMESTAMPTZ DEFAULT now()
);

-- Ensure updated_at trigger exists for industry_benchmarks
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_industry_benchmarks_updated_at ON industry_benchmarks;
CREATE TRIGGER trigger_industry_benchmarks_updated_at
    BEFORE UPDATE ON industry_benchmarks
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- =========================
-- Ensure reputation_calculations table exists
-- =========================
CREATE TABLE IF NOT EXISTS reputation_calculations (
                                                       id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                                       business_id BIGINT NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
                                                       reputul_rating DOUBLE PRECISION NOT NULL,
                                                       quality_score DOUBLE PRECISION NOT NULL,
                                                       velocity_score DOUBLE PRECISION NOT NULL,
                                                       responsiveness_score DOUBLE PRECISION NOT NULL,
                                                       composite_score DOUBLE PRECISION NOT NULL,
                                                       total_reviews INTEGER NOT NULL,
                                                       positive_reviews INTEGER NOT NULL,
                                                       reviews_last_90d INTEGER NOT NULL,
                                                       avg_response_time_hours DOUBLE PRECISION,
                                                       calculation_reason VARCHAR(100), -- 'scheduled', 'new_review', 'manual'
                                                       created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_reputation_calculations_business_id ON reputation_calculations(business_id);
CREATE INDEX IF NOT EXISTS idx_reputation_calculations_created_at ON reputation_calculations(created_at);

-- =========================
-- Insert initial industry benchmarks if they don't exist
-- =========================
INSERT INTO industry_benchmarks (industry, median_reviews_per_30d, percentile_25_reviews, percentile_75_reviews, sample_size)
VALUES
    ('roofing', 3.2, 1.5, 6.0, 100),
    ('hvac', 4.1, 2.0, 7.5, 100),
    ('plumbing', 3.8, 1.8, 6.8, 100),
    ('landscaping', 2.9, 1.2, 5.2, 100),
    ('electrical', 3.5, 1.6, 6.2, 100),
    ('general_contractor', 2.8, 1.1, 5.0, 100),
    ('painting', 3.1, 1.4, 5.5, 100),
    ('flooring', 2.7, 1.0, 4.8, 100),
    ('windows_doors', 2.5, 0.9, 4.2, 100),
    ('cleaning', 4.5, 2.2, 8.0, 100)
ON CONFLICT (industry) DO NOTHING;

-- =========================
-- Add comments for documentation
-- =========================
COMMENT ON COLUMN businesses.reputul_rating IS 'Wilson-based public rating 0-5 with recency weighting';
COMMENT ON COLUMN businesses.reputation_score_composite IS 'Owner-facing 0-100 score: 60% quality + 25% velocity + 15% responsiveness';
COMMENT ON COLUMN businesses.last_reputation_update IS 'Timestamp of last reputation calculation';
COMMENT ON TABLE industry_benchmarks IS 'Industry medians for velocity scoring normalization';
COMMENT ON TABLE reputation_calculations IS 'History of reputation calculations for debugging and trends';