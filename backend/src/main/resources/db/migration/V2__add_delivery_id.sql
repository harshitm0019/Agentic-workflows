-- Add delivery_id column to workflow_runs for webhook deduplication
ALTER TABLE workflow_runs ADD COLUMN IF NOT EXISTS delivery_id TEXT UNIQUE;
