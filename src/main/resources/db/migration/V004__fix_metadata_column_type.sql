-- Flyway migration: V004__fix_metadata_column_type.sql
-- Fix metadata column type mismatch between database (JSON) and entity (JSONB)

-- Change metadata column from JSON to JSONB to match Java entity expectation
-- This is safe since JSON can be cast to JSONB
ALTER TABLE usage_events
    ALTER COLUMN metadata TYPE JSONB USING metadata::JSONB;