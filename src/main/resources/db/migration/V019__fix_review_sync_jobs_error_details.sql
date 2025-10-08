-- Fix error_details column type mismatch
-- Change from jsonb to text since we're just storing error messages
ALTER TABLE review_sync_jobs
    ALTER COLUMN error_details TYPE TEXT;