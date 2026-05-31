-- Agentic Workflows Platform - Initial Schema
-- Creates tables for workflow runs, steps, review items, and Gemini usage tracking
-- This migration targets PostgreSQL (Supabase)

-- ============================================================
-- Table: workflow_runs
-- Stores each execution of a workflow
-- ============================================================
CREATE TABLE workflow_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_name   TEXT NOT NULL,
    trigger_event   TEXT NOT NULL,
    trigger_payload JSONB,
    status          TEXT NOT NULL DEFAULT 'running',
    current_step    INTEGER NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    error           TEXT,

    CONSTRAINT chk_workflow_runs_status CHECK (status IN ('running', 'paused_for_review', 'completed', 'failed'))
);

CREATE INDEX idx_workflow_runs_status ON workflow_runs(status);
CREATE INDEX idx_workflow_runs_started_at ON workflow_runs(started_at DESC);

-- ============================================================
-- Table: workflow_steps
-- Stores output from each completed step in a run
-- ============================================================
CREATE TABLE workflow_steps (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_run_id UUID NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    step_index      INTEGER NOT NULL,
    agent_name      TEXT NOT NULL,
    input           JSONB,
    output          JSONB,
    status          TEXT NOT NULL DEFAULT 'completed',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    error           TEXT,

    CONSTRAINT chk_workflow_steps_status CHECK (status IN ('completed', 'failed', 'timed_out', 'skipped'))
);

CREATE INDEX idx_workflow_steps_run_id ON workflow_steps(workflow_run_id);
CREATE INDEX idx_workflow_steps_run_step ON workflow_steps(workflow_run_id, step_index);

-- ============================================================
-- Table: review_items
-- Queue of items waiting for human approval
-- ============================================================
CREATE TABLE review_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_run_id  UUID NOT NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    workflow_step_id UUID NOT NULL REFERENCES workflow_steps(id) ON DELETE CASCADE,
    agent_name       TEXT NOT NULL,
    review_type      TEXT NOT NULL,
    payload          JSONB,
    status           TEXT NOT NULL DEFAULT 'pending',
    decision_reason  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at       TIMESTAMPTZ,

    CONSTRAINT chk_review_items_status CHECK (status IN ('pending', 'approved', 'rejected'))
);

CREATE INDEX idx_review_items_status ON review_items(status);
CREATE INDEX idx_review_items_run_id ON review_items(workflow_run_id);

-- ============================================================
-- Table: gemini_usage
-- Tracks daily API consumption
-- ============================================================
CREATE TABLE gemini_usage (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    date          DATE NOT NULL UNIQUE,
    request_count INTEGER NOT NULL DEFAULT 0,
    total_tokens  INTEGER NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_gemini_usage_date ON gemini_usage(date);
