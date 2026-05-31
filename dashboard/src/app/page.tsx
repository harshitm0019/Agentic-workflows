'use client';

import { useEffect, useState } from 'react';
import { WorkflowRun, listWorkflows } from '@/lib/api';
import { subscribeToWorkflowRuns, unsubscribe } from '@/lib/supabase';
import { WorkflowCard } from '@/components/WorkflowCard';
import { UsageStats } from '@/components/UsageStats';

export default function WorkflowListPage() {
  const [workflows, setWorkflows] = useState<WorkflowRun[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listWorkflows()
      .then(setWorkflows)
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));

    const channel = subscribeToWorkflowRuns(({ eventType, new: row }) => {
      if (eventType === 'INSERT') {
        setWorkflows((prev) => [
          {
            id: row.id,
            workflowName: row.workflow_name,
            triggerEvent: row.trigger_event,
            triggerPayload: row.trigger_payload,
            status: row.status,
            currentStep: row.current_step,
            startedAt: row.started_at,
            completedAt: row.completed_at,
            error: row.error,
          },
          ...prev,
        ]);
      } else if (eventType === 'UPDATE') {
        setWorkflows((prev) =>
          prev.map((w) =>
            w.id === row.id
              ? {
                  ...w,
                  status: row.status,
                  currentStep: row.current_step,
                  completedAt: row.completed_at,
                  error: row.error,
                }
              : w
          )
        );
      }
    });

    return () => {
      unsubscribe(channel);
    };
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <p className="text-gray-500">Loading workflows...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-4">
        <p className="text-red-700">Error loading workflows: {error}</p>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <section>
        <h1 className="text-2xl font-bold mb-4">Workflows</h1>
        {workflows.length === 0 ? (
          <div className="bg-white rounded-lg border border-gray-200 p-8 text-center">
            <p className="text-gray-500">No workflow runs yet.</p>
            <p className="text-gray-400 text-sm mt-1">
              Workflows will appear here when triggered by GitHub events.
            </p>
          </div>
        ) : (
          <div className="grid gap-4">
            {workflows.map((workflow) => (
              <WorkflowCard key={workflow.id} workflow={workflow} />
            ))}
          </div>
        )}
      </section>

      <section id="usage">
        <h2 className="text-2xl font-bold mb-4">Usage</h2>
        <UsageStats />
      </section>
    </div>
  );
}
