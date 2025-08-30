-- Flyway migration: V005__add_type_column_alias.sql
-- Add 'type' column as an alias/copy of 'usage_type' for compatibility

-- Add the type column that Hibernate is expecting
ALTER TABLE usage_events ADD COLUMN type VARCHAR(50);

-- Copy values from usage_type to type
UPDATE usage_events SET type = usage_type::VARCHAR;

-- Add the same constraint as usage_type
ALTER TABLE usage_events ADD CONSTRAINT check_usage_events_type
    CHECK (type IN (
                    'SMS_REVIEW_REQUEST_SENT',
                    'EMAIL_REVIEW_REQUEST_SENT',
                    'REVIEW_REQUEST_SENT',
                    'CUSTOMER_CREATED'
        ));

-- Make it NOT NULL
ALTER TABLE usage_events ALTER COLUMN type SET NOT NULL;

-- Create trigger to keep them in sync (optional, for transition period)
CREATE OR REPLACE FUNCTION sync_usage_type_columns()
    RETURNS TRIGGER AS $$
BEGIN
    -- Sync type to usage_type
    IF NEW.type IS NOT NULL AND NEW.type != OLD.type THEN
        NEW.usage_type = NEW.type::VARCHAR;
    END IF;

    -- Sync usage_type to type
    IF NEW.usage_type IS NOT NULL AND NEW.usage_type != OLD.usage_type THEN
        NEW.type = NEW.usage_type::VARCHAR;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply trigger
DROP TRIGGER IF EXISTS trigger_sync_usage_type_columns ON usage_events;
CREATE TRIGGER trigger_sync_usage_type_columns
    BEFORE UPDATE ON usage_events
    FOR EACH ROW
EXECUTE FUNCTION sync_usage_type_columns();