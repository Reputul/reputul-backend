-- Ensure automation_triggers.updated_at exists on DBs created before this migration
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_name = 'automation_triggers'
              AND column_name = 'updated_at'
        ) THEN
            -- add column nullable first to allow backfill
            ALTER TABLE automation_triggers
                ADD COLUMN updated_at TIMESTAMPTZ;

            -- backfill with a sensible timestamp (created_at if present, otherwise now)
            UPDATE automation_triggers
            SET updated_at = COALESCE(created_at, NOW());

            -- make it NOT NULL and set a default for new rows
            ALTER TABLE automation_triggers
                ALTER COLUMN updated_at SET NOT NULL,
                ALTER COLUMN updated_at SET DEFAULT NOW();
        END IF;
    END $$;
