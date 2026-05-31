'use client';

import Link from 'next/link';
import { WorkflowRun } from '@/lib/api';

const statusConfig: Record<string, { color: string; label: string }> = {
  running: { color: 'bg-blue-100 text-blue-800', label: 'Running' },
  paused_for_review: { color: 'bg-yellow-100 text-yellow-800', label: 'Awaiting Review' },
  completed: { color: 'bg-green-100 text-green-800', label: 'Completed' },
  failed: { color: 'bg-red-100 text-red-800', label: 'Failed' },
};

type WorkflowCardProps = {
  workflow: WorkflowRun;
};

export function WorkflowCard({ workflow }: WorkflowCardProps) {
  const status = statusConfig[workflow.status] || {
    color: 'bg-gray-100 text-gray-800',
    label: workflow.status,
  };

  return (
    <Link href={`/workflows/${workflow.id}`}>
      <div className="bg-white rounded-lg border border-gray-200 p-4 hover:border-gray-300 transition-colors cursor-pointer">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="font-semibold text-gray-900">
              {workflow.workflowName}
            </h3>
            <p className="text-sm text-gray-500 mt-0.5">
              Trigger: {workflow.triggerEvent} &middot;{' '}
              {new Date(workflow.startedAt).toLocaleString()}
            </p>
          </div>
          <span
            className={`text-xs px-2.5 py-1 rounded-full font-medium ${status.color}`}
          >
            {status.label}
          </span>
        </div>
        {workflow.error && (
          <p className="text-red-600 text-sm mt-2 truncate">{workflow.error}</p>
        )}
      </div>
    </Link>
  );
}
