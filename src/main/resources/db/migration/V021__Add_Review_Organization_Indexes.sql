-- Add indexes to optimize organization-aware review queries and improve performance

-- =========================
-- ADD ORGANIZATION_ID TO REVIEWS TABLE (if not exists)
-- =========================

-- Add organization_id column if it doesn't exist
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS organization_id BIGINT;

-- Add foreign key constraint if it doesn't exist
DO $$
    BEGIN
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.table_constraints
            WHERE constraint_name = 'fk_reviews_organization'
              AND table_name = 'reviews'
        ) THEN
            ALTER TABLE reviews ADD CONSTRAINT fk_reviews_organization
                FOREIGN KEY (organization_id) REFERENCES organizations(id);
        END IF;
    END $$;

-- Populate organization_id from businesses table where it's null
UPDATE reviews r
SET organization_id = b.organization_id
FROM businesses b
WHERE r.business_id = b.id
  AND r.organization_id IS NULL
  AND b.organization_id IS NOT NULL;

-- Make organization_id NOT NULL after population (only if all rows have values)
DO $$
    BEGIN
        -- Only set NOT NULL if no rows have NULL organization_id
        IF NOT EXISTS (SELECT 1 FROM reviews WHERE organization_id IS NULL) THEN
            ALTER TABLE reviews ALTER COLUMN organization_id SET NOT NULL;
        END IF;
    END $$;

-- =========================
-- CREATE PERFORMANCE INDEXES
-- =========================

-- Index for reviews by business and organization (most common query)
-- Supports: findByBusinessIdAndOrganizationId queries
CREATE INDEX IF NOT EXISTS idx_reviews_business_organization
    ON reviews(business_id, organization_id);

-- Composite index for reviews by business, organization, and creation date
-- Supports: findByBusinessIdAndOrganizationIdOrderByCreatedAtDesc queries
CREATE INDEX IF NOT EXISTS idx_reviews_business_org_created_at
    ON reviews(business_id, organization_id, created_at DESC);

-- Index for reviews by organization and creation date (organization-level queries)
-- Supports: findByOrganizationIdOrderByCreatedAtDesc queries
CREATE INDEX IF NOT EXISTS idx_reviews_organization_created_at
    ON reviews(organization_id, created_at DESC);

-- Index for reviews by business, organization, and source
-- Supports: findByBusinessIdAndOrganizationIdAndSource queries
CREATE INDEX IF NOT EXISTS idx_reviews_business_org_source
    ON reviews(business_id, organization_id, source)
    WHERE source IS NOT NULL;

-- Index for reviews by business, organization, and rating
-- Supports: findByBusinessIdAndOrganizationIdAndRatingBetween queries
CREATE INDEX IF NOT EXISTS idx_reviews_business_org_rating
    ON reviews(business_id, organization_id, rating)
    WHERE rating IS NOT NULL;

-- =========================
-- BASIC FOREIGN KEY INDEXES (if they don't exist)
-- =========================

-- Ensure we have basic indexes for foreign keys
CREATE INDEX IF NOT EXISTS idx_reviews_business_id
    ON reviews(business_id);

CREATE INDEX IF NOT EXISTS idx_reviews_organization_id
    ON reviews(organization_id);

-- Index for source and source_review_id (deduplication queries)
CREATE INDEX IF NOT EXISTS idx_reviews_source_dedup
    ON reviews(business_id, source, source_review_id)
    WHERE source IS NOT NULL AND source_review_id IS NOT NULL;

-- =========================
-- CREATE TRIGGER TO MAINTAIN ORGANIZATION_ID
-- =========================

-- Create or replace function to maintain organization_id in reviews
CREATE OR REPLACE FUNCTION sync_review_organization_id()
    RETURNS TRIGGER AS $$
BEGIN
    -- For INSERT: set organization_id from business
    IF TG_OP = 'INSERT' THEN
        SELECT organization_id INTO NEW.organization_id
        FROM businesses
        WHERE id = NEW.business_id;
        RETURN NEW;
    END IF;

    -- For UPDATE: update organization_id if business_id changed
    IF TG_OP = 'UPDATE' AND OLD.business_id != NEW.business_id THEN
        SELECT organization_id INTO NEW.organization_id
        FROM businesses
        WHERE id = NEW.business_id;
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger to automatically maintain organization_id
DROP TRIGGER IF EXISTS reviews_organization_sync_trigger ON reviews;
CREATE TRIGGER reviews_organization_sync_trigger
    BEFORE INSERT OR UPDATE ON reviews
    FOR EACH ROW
EXECUTE FUNCTION sync_review_organization_id();

-- =========================
-- COMMENTS FOR DOCUMENTATION
-- =========================

COMMENT ON INDEX idx_reviews_business_organization IS 'Optimizes organization-aware review queries by business';
COMMENT ON INDEX idx_reviews_business_org_created_at IS 'Optimizes paginated review queries with organization scoping';
COMMENT ON INDEX idx_reviews_organization_created_at IS 'Optimizes organization-level review dashboards';
COMMENT ON INDEX idx_reviews_source_dedup IS 'Optimizes review deduplication during imports';
COMMENT ON COLUMN reviews.organization_id IS 'Denormalized organization_id for faster queries without JOINs';
COMMENT ON TRIGGER reviews_organization_sync_trigger ON reviews IS 'Maintains organization_id consistency with business relationship';