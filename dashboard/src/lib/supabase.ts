import { createClient, RealtimeChannel } from '@supabase/supabase-js';

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!;

export const supabase = createClient(supabaseUrl, supabaseAnonKey);

export type WorkflowRunRow = {
  id: string;
  workflow_name: string;
  trigger_event: string;
  trigger_payload: Record<string, unknown>;
  status: 'running' | 'paused_for_review' | 'completed' | 'failed';
  current_step: number;
  started_at: string;
  completed_at: string | null;
  error: string | null;
};

export type WorkflowStepRow = {
  id: string;
  workflow_run_id: string;
  step_index: number;
  agent_name: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  status: 'completed' | 'failed' | 'timed_out' | 'skipped';
  started_at: string;
  completed_at: string | null;
  error: string | null;
};

export type ReviewItemRow = {
  id: string;
  workflow_run_id: string;
  workflow_step_id: string;
  agent_name: string;
  review_type: string;
  payload: Record<string, unknown>;
  status: 'pending' | 'approved' | 'rejected';
  decision_reason: string | null;
  created_at: string;
  decided_at: string | null;
};

export type RealtimeCallback<T> = (payload: {
  eventType: 'INSERT' | 'UPDATE' | 'DELETE';
  new: T;
  old: Partial<T>;
}) => void;

/**
 * Subscribe to real-time changes on workflow_runs table.
 */
export function subscribeToWorkflowRuns(
  callback: RealtimeCallback<WorkflowRunRow>
): RealtimeChannel {
  const channel = supabase
    .channel('workflow-runs-changes')
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'workflow_runs' },
      (payload) => {
        callback({
          eventType: payload.eventType as 'INSERT' | 'UPDATE' | 'DELETE',
          new: payload.new as WorkflowRunRow,
          old: payload.old as Partial<WorkflowRunRow>,
        });
      }
    )
    .subscribe();

  return channel;
}

/**
 * Subscribe to real-time changes on review_items table.
 */
export function subscribeToReviewItems(
  callback: RealtimeCallback<ReviewItemRow>
): RealtimeChannel {
  const channel = supabase
    .channel('review-items-changes')
    .on(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'review_items' },
      (payload) => {
        callback({
          eventType: payload.eventType as 'INSERT' | 'UPDATE' | 'DELETE',
          new: payload.new as ReviewItemRow,
          old: payload.old as Partial<ReviewItemRow>,
        });
      }
    )
    .subscribe();

  return channel;
}

/**
 * Subscribe to real-time changes on a single workflow_run by ID.
 */
export function subscribeToWorkflowRun(
  runId: string,
  callback: RealtimeCallback<WorkflowRunRow>
): RealtimeChannel {
  const channel = supabase
    .channel(`workflow-run-${runId}`)
    .on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'workflow_runs',
        filter: `id=eq.${runId}`,
      },
      (payload) => {
        callback({
          eventType: payload.eventType as 'INSERT' | 'UPDATE' | 'DELETE',
          new: payload.new as WorkflowRunRow,
          old: payload.old as Partial<WorkflowRunRow>,
        });
      }
    )
    .subscribe();

  return channel;
}

/**
 * Subscribe to real-time changes on workflow_steps for a specific workflow run.
 */
export function subscribeToWorkflowSteps(
  runId: string,
  callback: RealtimeCallback<WorkflowStepRow>
): RealtimeChannel {
  const channel = supabase
    .channel(`workflow-steps-${runId}`)
    .on(
      'postgres_changes',
      {
        event: '*',
        schema: 'public',
        table: 'workflow_steps',
        filter: `workflow_run_id=eq.${runId}`,
      },
      (payload) => {
        callback({
          eventType: payload.eventType as 'INSERT' | 'UPDATE' | 'DELETE',
          new: payload.new as WorkflowStepRow,
          old: payload.old as Partial<WorkflowStepRow>,
        });
      }
    )
    .subscribe();

  return channel;
}

/**
 * Unsubscribe from a realtime channel.
 */
export function unsubscribe(channel: RealtimeChannel): void {
  supabase.removeChannel(channel);
}
