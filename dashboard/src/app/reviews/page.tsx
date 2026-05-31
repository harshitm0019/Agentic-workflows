'use client';

import { useEffect, useState } from 'react';
import { ReviewItem as ReviewItemType, listPendingReviews } from '@/lib/api';
import { subscribeToReviewItems, unsubscribe } from '@/lib/supabase';
import { ReviewItem } from '@/components/ReviewItem';

export default function ReviewsPage() {
  const [reviews, setReviews] = useState<ReviewItemType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listPendingReviews()
      .then((items) => {
        // Sort by createdAt ascending (oldest first)
        const sorted = [...items].sort(
          (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
        );
        setReviews(sorted);
      })
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));

    const channel = subscribeToReviewItems(({ eventType, new: row }) => {
      if (eventType === 'INSERT' && row.status === 'pending') {
        const newItem: ReviewItemType = {
          id: row.id,
          workflowRunId: row.workflow_run_id,
          workflowStepId: row.workflow_step_id,
          agentName: row.agent_name,
          reviewType: row.review_type,
          payload: row.payload,
          status: row.status,
          decisionReason: row.decision_reason,
          createdAt: row.created_at,
          decidedAt: row.decided_at,
        };
        // Insert in sorted position (oldest first)
        setReviews((prev) => {
          const updated = [...prev, newItem];
          return updated.sort(
            (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
          );
        });
      } else if (eventType === 'UPDATE') {
        // Remove items that are no longer pending
        if (row.status !== 'pending') {
          setReviews((prev) => prev.filter((r) => r.id !== row.id));
        }
      }
    });

    return () => {
      unsubscribe(channel);
    };
  }, []);

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center py-16">
        <div className="animate-pulse flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-gray-300 border-t-gray-600 rounded-full animate-spin" />
          <p className="text-gray-500 text-sm">Loading pending reviews...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-5">
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-red-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <p className="text-red-700 font-medium">Failed to load reviews</p>
        </div>
        <p className="text-red-600 text-sm mt-1 ml-7">{error}</p>
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Review Queue</h1>
          <p className="text-sm text-gray-500 mt-1">
            {reviews.length === 0
              ? 'No items pending review'
              : `${reviews.length} item${reviews.length !== 1 ? 's' : ''} awaiting decision`}
          </p>
        </div>
      </div>

      {reviews.length === 0 ? (
        <div className="bg-white rounded-lg border border-gray-200 p-12 text-center">
          <svg
            className="w-12 h-12 text-gray-300 mx-auto mb-4"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={1.5}
              d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
          <p className="text-gray-500 font-medium">All clear</p>
          <p className="text-gray-400 text-sm mt-1">
            Items requiring approval will appear here in real-time.
          </p>
        </div>
      ) : (
        <div className="grid gap-4">
          {reviews.map((review) => (
            <ReviewItem
              key={review.id}
              review={review}
              onDecision={(id) =>
                setReviews((prev) => prev.filter((r) => r.id !== id))
              }
            />
          ))}
        </div>
      )}
    </div>
  );
}
