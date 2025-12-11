-- V025__make_review_requests_updated_at_nullable.sql
-- Allow updated_at to be NULL for newly created review requests

ALTER TABLE review_requests
    ALTER COLUMN updated_at DROP NOT NULL;