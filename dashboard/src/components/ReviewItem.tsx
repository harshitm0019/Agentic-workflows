'use client';

import { useState } from 'react';
import { ReviewItem as ReviewItemType, approveReview, rejectReview } from '@/lib/api';
import { DiffViewer } from './DiffViewer';

type ReviewItemProps = {
  review: ReviewItemType;
  onDecision: (id: string) => void;
};

function ReviewTypeBadge({ type }: { type: string }) {
  const label = type === 'approve_patch' ? 'Patch Approval' : type.replace(/_/g, ' ');
  const badgeClass =
    type === 'approve_patch'
      ? 'bg-amber-100 text-amber-800'
      : 'bg-blue-100 text-blue-800';

  return (
    <span className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${badgeClass}`}>
      {label}
    </span>
  );
}

export function ReviewItem({ review, onDecision }: ReviewItemProps) {
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleApprove = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const res = await approveReview(review.id);
      setResult(res.commitSha ? `Approved — Commit: ${res.commitSha}` : res.message || 'Approved');
      setTimeout(() => onDecision(review.id), 1500);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setSubmitting(false);
    }
  };

  const handleReject = async () => {
    if (!rejectReason.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await rejectReview(review.id, rejectReason);
      setResult('Rejected');
      setTimeout(() => onDecision(review.id), 1500);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error');
    } finally {
      setSubmitting(false);
    }
  };

  const diffContent =
    typeof review.payload?.patch === 'string' ? review.payload.patch : null;

  // Derive workflow name from payload if available, otherwise show truncated run ID
  const workflowName =
    typeof review.payload?.workflowName === 'string'
      ? review.payload.workflowName
      : review.workflowRunId
        ? `Run ${review.workflowRunId.slice(0, 8)}`
        : 'Unknown';

  return (
    <div className="bg-white rounded-lg border border-gray-200 shadow-sm hover:shadow-md transition-shadow">
      <div className="p-5">
        {/* Header */}
        <div className="flex items-start justify-between mb-3">
          <div className="space-y-1">
            <h3 className="text-base font-semibold text-gray-900">
              {review.agentName}
            </h3>
            <div className="flex items-center gap-2 text-sm text-gray-500">
              <span className="inline-flex items-center gap-1">
                <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h7" />
                </svg>
                {workflowName}
              </span>
              <span className="text-gray-300">·</span>
              <span>{new Date(review.createdAt).toLocaleString()}</span>
            </div>
          </div>
          <ReviewTypeBadge type={review.reviewType} />
        </div>

        {/* Content */}
        {diffContent && <DiffViewer diff={diffContent} />}

        {!diffContent && review.payload && (
          <div className="mt-3 bg-gray-50 border border-gray-200 rounded-lg overflow-auto max-h-56">
            <pre className="text-xs p-4 font-mono text-gray-700">
              {JSON.stringify(review.payload, null, 2)}
            </pre>
          </div>
        )}

        {/* Result / Error / Actions */}
        {error && (
          <div className="mt-3 bg-red-50 border border-red-200 rounded-md px-3 py-2">
            <p className="text-sm text-red-700">{error}</p>
          </div>
        )}

        {result ? (
          <div className="mt-3 bg-gray-50 border border-gray-200 rounded-md px-3 py-2">
            <p className="text-sm font-medium text-gray-700">{result}</p>
          </div>
        ) : (
          <div className="mt-4 flex items-center gap-3">
            <button
              onClick={handleApprove}
              disabled={submitting}
              className="inline-flex items-center gap-1.5 px-4 py-2 bg-green-600 text-white text-sm font-medium rounded-md hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
              Approve
            </button>

            {!rejecting ? (
              <button
                onClick={() => setRejecting(true)}
                disabled={submitting}
                className="inline-flex items-center gap-1.5 px-4 py-2 bg-red-600 text-white text-sm font-medium rounded-md hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
                Reject
              </button>
            ) : (
              <div className="flex items-center gap-2 flex-1">
                <input
                  type="text"
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && rejectReason.trim()) handleReject();
                  }}
                  placeholder="Reason for rejection..."
                  className="flex-1 border border-gray-300 rounded-md px-3 py-1.5 text-sm focus:ring-2 focus:ring-red-500 focus:border-red-500 outline-none"
                  autoFocus
                />
                <button
                  onClick={handleReject}
                  disabled={submitting || !rejectReason.trim()}
                  className="px-3 py-1.5 bg-red-600 text-white text-sm font-medium rounded-md hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                >
                  Confirm
                </button>
                <button
                  onClick={() => {
                    setRejecting(false);
                    setRejectReason('');
                  }}
                  className="px-3 py-1.5 text-gray-600 text-sm hover:text-gray-800 transition-colors"
                >
                  Cancel
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
