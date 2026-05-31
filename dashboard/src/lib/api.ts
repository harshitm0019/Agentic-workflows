const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

// --- Types ---

export type WorkflowStatus = 'running' | 'paused_for_review' | 'completed' | 'failed';

export type WorkflowRun = {
  id: string;
  workflowName: string;
  triggerEvent: string;
  triggerPayload: Record<string, unknown>;
  status: WorkflowStatus;
  currentStep: number;
  startedAt: string;
  completedAt: string | null;
  error: string | null;
};

export type WorkflowStepRecord = {
  id: string;
  workflowRunId: string;
  stepIndex: number;
  agentName: string;
  input: Record<string, unknown>;
  output: Record<string, unknown>;
  status: 'completed' | 'failed' | 'timed_out' | 'skipped';
  startedAt: string;
  completedAt: string | null;
  error: string | null;
};

export type WorkflowRunDetail = WorkflowRun & {
  steps: WorkflowStepRecord[];
};

export type ReviewItem = {
  id: string;
  workflowRunId: string;
  workflowStepId: string;
  agentName: string;
  reviewType: string;
  payload: Record<string, unknown>;
  status: 'pending' | 'approved' | 'rejected';
  decisionReason: string | null;
  createdAt: string;
  decidedAt: string | null;
};

export type UsageStats = {
  date: string;
  requestsMade: number;
  requestLimit: number;
  tokensUsed: number;
  tokenLimit: number;
  warningThreshold: number;
};

export type ApproveResponse = {
  commitSha?: string;
  message: string;
};

export type RejectResponse = {
  message: string;
};

// --- API Functions ---

async function fetchJson<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`API error ${response.status}: ${errorText}`);
  }

  return response.json() as Promise<T>;
}

/**
 * GET /api/workflows - List all workflow runs
 */
export async function listWorkflows(): Promise<WorkflowRun[]> {
  return fetchJson<WorkflowRun[]>(`${API_BASE_URL}/api/workflows`);
}

/**
 * GET /api/workflows/{id} - Get workflow run details with steps
 */
export async function getWorkflowDetail(id: string): Promise<WorkflowRunDetail> {
  return fetchJson<WorkflowRunDetail>(`${API_BASE_URL}/api/workflows/${id}`);
}

/**
 * GET /api/reviews/pending - List pending review items
 */
export async function listPendingReviews(): Promise<ReviewItem[]> {
  return fetchJson<ReviewItem[]>(`${API_BASE_URL}/api/reviews/pending`);
}

/**
 * POST /api/reviews/{id}/approve - Approve a review item
 */
export async function approveReview(id: string): Promise<ApproveResponse> {
  return fetchJson<ApproveResponse>(`${API_BASE_URL}/api/reviews/${id}/approve`, {
    method: 'POST',
  });
}

/**
 * POST /api/reviews/{id}/reject - Reject a review item with reason
 */
export async function rejectReview(id: string, reason: string): Promise<RejectResponse> {
  return fetchJson<RejectResponse>(`${API_BASE_URL}/api/reviews/${id}/reject`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  });
}

/**
 * GET /api/usage - Get current Gemini usage stats
 */
export async function getUsageStats(): Promise<UsageStats> {
  return fetchJson<UsageStats>(`${API_BASE_URL}/api/usage`);
}
