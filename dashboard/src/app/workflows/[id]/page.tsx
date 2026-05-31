'use client';

import { useEffect, useState } from 'react';
import { useParams } from 'next/navigation';
import { WorkflowRunDetail, WorkflowStepRecord, getWorkflowDetail } from '@/lib/api';
import {
  subscribeToWorkflowRun,
  subscribeToWorkflowSteps,
  unsubscribe,
  WorkflowStepRow,
} from '@/lib/supabase';

const statusColors: Record<string, string> = {
  completed: 'bg-green-100 text-green-800',
  failed: 'bg-red-100 text-red-800',
  timed_out: 'bg-orange-100 text-orange-800',
  skipped: 'bg-gray-100 text-gray-600',
};

function mapStepRow(row: WorkflowStepRow): WorkflowStepRecord {
  return {
    id: row.id,
    workflowRunId: row.workflow_run_id,
    stepIndex: row.step_index,
    agentName: row.agent_name,
    input: row.input,
    output: row.output,
    status: row.status,
    startedAt: row.started_at,
    completedAt: row.completed_at,
    error: row.error,
  };
}

export default function WorkflowDetailPage() {
  const params = useParams();
  const id = params.id as string;
  const [detail, setDetail] = useState<WorkflowRunDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;

    getWorkflowDetail(id)
      .then(setDetail)
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));

    // Subscribe to workflow run status updates
    const runChannel = subscribeToWorkflowRun(id, ({ eventType, new: row }) => {
      if (eventType === 'UPDATE') {
        setDetail((prev) =>
          prev
            ? {
                ...prev,
                status: row.status,
                currentStep: row.current_step,
                completedAt: row.completed_at,
                error: row.error,
              }
            : prev
        );
      }
    });

    // Subscribe to workflow step changes
    const stepsChannel = subscribeToWorkflowSteps(id, ({ eventType, new: row }) => {
      if (eventType === 'INSERT') {
        setDetail((prev) => {
          if (!prev) return prev;
          const newStep = mapStepRow(row);
          // Avoid duplicates
          if (prev.steps.some((s) => s.id === newStep.id)) return prev;
          return { ...prev, steps: [...prev.steps, newStep] };
        });
      } else if (eventType === 'UPDATE') {
        setDetail((prev) => {
          if (!prev) return prev;
          return {
            ...prev,
            steps: prev.steps.map((s) =>
              s.id === row.id ? mapStepRow(row) : s
            ),
          };
        });
      }
    });

    return () => {
      unsubscribe(runChannel);
      unsubscribe(stepsChannel);
    };
  }, [id]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <p className="text-gray-500">Loading workflow details...</p>
      </div>
    );
  }

  if (error || !detail) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-4">
        <p className="text-red-700">{error || 'Workflow not found'}</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{detail.workflowName}</h1>
        <p className="text-gray-500 text-sm mt-1">
          Triggered by: {detail.triggerEvent} &middot; Started:{' '}
          {new Date(detail.startedAt).toLocaleString()}
        </p>
      </div>

      <div className="bg-white rounded-lg border border-gray-200 p-4">
        <h2 className="font-semibold text-lg mb-3">Steps</h2>
        {detail.steps.length === 0 ? (
          <p className="text-gray-500 text-sm">No steps recorded yet.</p>
        ) : (
          <div className="space-y-3">
            {detail.steps.map((step) => (
              <div
                key={step.id}
                className="border border-gray-100 rounded-md p-3"
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium">
                    Step {step.stepIndex + 1}: {step.agentName}
                  </span>
                  <span
                    className={`text-xs px-2 py-0.5 rounded-full font-medium ${
                      statusColors[step.status] || 'bg-blue-100 text-blue-800'
                    }`}
                  >
                    {step.status}
                  </span>
                </div>
                {step.error && (
                  <p className="text-red-600 text-sm mt-1">{step.error}</p>
                )}
                {step.output && Object.keys(step.output).length > 0 && (
                  <details className="mt-2">
                    <summary className="text-sm text-gray-500 cursor-pointer">
                      View output
                    </summary>
                    <pre className="mt-1 text-xs bg-gray-50 p-2 rounded overflow-auto max-h-64">
                      {JSON.stringify(step.output, null, 2)}
                    </pre>
                  </details>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {detail.error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4">
          <h3 className="font-medium text-red-800">Error</h3>
          <p className="text-red-700 text-sm mt-1">{detail.error}</p>
        </div>
      )}
    </div>
  );
}
