-- ============================================================================
-- Migration: V024__add_google_places_fields.sql
-- Description: Add Google Places auto-detection fields to businesses table
-- Author: Reputul Team
-- Date: 2025-01-10
-- ============================================================================

-- Add new Google Places fields to businesses table
ALTER TABLE businesses
    ADD COLUMN IF NOT EXISTS google_review_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS google_review_short_url VARCHAR(300),
    ADD COLUMN IF NOT EXISTS google_search_url VARCHAR(500),
    ADD COLUMN IF NOT EXISTS google_place_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_place_formatted_address VARCHAR(500),
    ADD COLUMN IF NOT EXISTS google_place_types TEXT,
    ADD COLUMN IF NOT EXISTS google_place_last_synced TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS google_place_auto_detected BOOLEAN DEFAULT FALSE;

-- Add comment for documentation
COMMENT ON COLUMN businesses.google_review_url IS 'Direct Google review URL: https://search.google.com/local/writereview?placeid=PLACE_ID';
COMMENT ON COLUMN businesses.google_review_short_url IS 'User-provided g.page short URL: https://g.page/r/XXXXXXX/review';
COMMENT ON COLUMN businesses.google_search_url IS 'Fallback Google search URL when Place ID unavailable';
COMMENT ON COLUMN businesses.google_place_name IS 'Business name from Google Places API';
COMMENT ON COLUMN businesses.google_place_formatted_address IS 'Formatted address from Google Places API';
COMMENT ON COLUMN businesses.google_place_types IS 'Business types from Google Places (comma-separated)';
COMMENT ON COLUMN businesses.google_place_last_synced IS 'Last time we fetched Place details from Google';
COMMENT ON COLUMN businesses.google_place_auto_detected IS 'TRUE if Place ID was auto-detected, FALSE if manually entered';

-- Create index for faster lookups
CREATE INDEX IF NOT EXISTS idx_businesses_google_place_id ON businesses(google_place_id) WHERE google_place_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_businesses_google_place_last_synced ON businesses(google_place_last_synced);

-- ============================================================================
-- MIGRATION NOTES:
-- ============================================================================
-- This migration is SAFE and REVERSIBLE:
-- - All columns are nullable and have defaults
-- - Existing data is not modified
-- - Indexes are added with IF NOT EXISTS
-- - No breaking changes to existing code
--
-- Rollback (if needed):
-- ALTER TABLE businesses
-- DROP COLUMN IF EXISTS google_review_url,
-- DROP COLUMN IF EXISTS google_review_short_url,
-- DROP COLUMN IF EXISTS google_search_url,
-- DROP COLUMN IF EXISTS google_place_name,
-- DROP COLUMN IF EXISTS google_place_formatted_address,
-- DROP COLUMN IF EXISTS google_place_types,
-- DROP COLUMN IF EXISTS google_place_last_synced,
-- DROP COLUMN IF EXISTS google_place_auto_detected;
-- ============================================================================