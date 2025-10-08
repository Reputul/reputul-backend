-- Fix metadata column type from JSONB to TEXT
ALTER TABLE channel_credentials ALTER COLUMN metadata TYPE TEXT;