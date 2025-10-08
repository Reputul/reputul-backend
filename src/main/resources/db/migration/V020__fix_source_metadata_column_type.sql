-- Fix source_metadata column type in reviews table
-- Change from jsonb to text
ALTER TABLE reviews
    ALTER COLUMN source_metadata TYPE TEXT USING source_metadata::TEXT;