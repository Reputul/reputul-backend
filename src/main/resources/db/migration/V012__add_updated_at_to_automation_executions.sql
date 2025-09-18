-- Ensure automation_executions.updated_at exists on DBs created before V011
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_name = 'automation_executions'
              AND column_name = 'updated_at'
        ) THEN
            -- add column nullable first to allow backfill
            ALTER TABLE automation_executions
                ADD COLUMN updated_at TIMESTAMPTZ;

            -- backfill with the best available timestamp
            UPDATE automation_executions
            SET updated_at = GREATEST(
                    COALESCE(completed_at, started_at, created_at, NOW()),
                    created_at
                             );

            -- make it NOT NULL with a sensible default going forward
            ALTER TABLE automation_executions
                ALTER COLUMN updated_at SET NOT NULL,
                ALTER COLUMN updated_at SET DEFAULT NOW();
        END IF;
    END $$;
