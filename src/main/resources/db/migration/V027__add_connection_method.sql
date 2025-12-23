-- V027__add_connection_method.sql
-- Add connection method field to distinguish OAuth vs URL connections

ALTER TABLE channel_credentials
    ADD COLUMN IF NOT EXISTS connection_method VARCHAR(20) DEFAULT 'OAUTH';

COMMENT ON COLUMN channel_credentials.connection_method
    IS 'Connection method: OAUTH (fast, 15 min sync) or URL (slow, 2 hour sync)';

-- Set based on existing data
UPDATE channel_credentials
SET connection_method = CASE
                            WHEN access_token IS NOT NULL THEN 'OAUTH'
                            ELSE 'URL'
    END;

-- Add display_order if you want drag-and-drop UI in Settings
ALTER TABLE channel_credentials
    ADD COLUMN IF NOT EXISTS display_order INTEGER DEFAULT 0;

COMMENT ON COLUMN channel_credentials.display_order
    IS 'Display order in settings page (for drag and drop)';

-- Set initial display order based on creation date
UPDATE channel_credentials
SET display_order = sub.row_num - 1
FROM (
         SELECT id, ROW_NUMBER() OVER (PARTITION BY business_id ORDER BY created_at) as row_num
         FROM channel_credentials
     ) sub
WHERE channel_credentials.id = sub.id;