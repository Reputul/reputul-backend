-- V014: Add automation workflow timing fields to customers table
-- This migration adds fields to control when automation workflows should trigger

-- Add new columns for automation workflow control
ALTER TABLE customers
    ADD COLUMN service_completed_date TIMESTAMP WITH TIME ZONE,
    ADD COLUMN automation_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ready_for_automation BOOLEAN NOT NULL DEFAULT FALSE;

-- Create indexes for performance on automation queries
CREATE INDEX idx_customers_automation_triggered ON customers(automation_triggered);
CREATE INDEX idx_customers_ready_for_automation ON customers(ready_for_automation);
CREATE INDEX idx_customers_service_completed_date ON customers(service_completed_date);

-- Update existing customers based on their current status
-- Customers with COMPLETED status are assumed to be ready for automation
UPDATE customers
SET ready_for_automation = TRUE
WHERE status = 'COMPLETED';

-- For customers with service dates in the past, mark them as ready
UPDATE customers
SET ready_for_automation = TRUE
WHERE service_date < CURRENT_DATE
  AND ready_for_automation = FALSE;

-- For customers with COMPLETED status and no service_completed_date,
-- estimate it based on created_at or service_date
UPDATE customers
SET service_completed_date = COALESCE(
    -- If service_date is today or in the past, use service_date at 5 PM
        CASE
            WHEN service_date <= CURRENT_DATE
                THEN (service_date + INTERVAL '17 hours')::TIMESTAMP WITH TIME ZONE
            ELSE NULL
            END,
    -- Fallback to created_at if service_date is in future
        created_at
                             )
WHERE status = 'COMPLETED'
  AND service_completed_date IS NULL;

-- Add comments for documentation
COMMENT ON COLUMN customers.service_completed_date IS 'Timestamp when the service was marked as completed';
COMMENT ON COLUMN customers.automation_triggered IS 'Flag to prevent duplicate automation triggers for the same customer';
COMMENT ON COLUMN customers.ready_for_automation IS 'Flag indicating customer is ready for automation workflows (service completed or past service date)';

-- Verify the migration worked correctly
-- This will be logged but won't affect the migration
DO $$
    DECLARE
        total_customers INTEGER;
        ready_customers INTEGER;
        completed_customers INTEGER;
    BEGIN
        SELECT COUNT(*) INTO total_customers FROM customers;
        SELECT COUNT(*) INTO ready_customers FROM customers WHERE ready_for_automation = TRUE;
        SELECT COUNT(*) INTO completed_customers FROM customers WHERE status = 'COMPLETED';

        RAISE NOTICE 'Migration V014 completed successfully:';
        RAISE NOTICE 'Total customers: %', total_customers;
        RAISE NOTICE 'Ready for automation: %', ready_customers;
        RAISE NOTICE 'Completed status: %', completed_customers;
    END $$;