'use client';

import { useEffect, useState } from 'react';
import { UsageStats as UsageStatsType, getUsageStats } from '@/lib/api';

function ProgressBar({ percent, label, current, limit }: {
  percent: number;
  label: string;
  current: number;
  limit: number;
}) {
  const getBarColor = (pct: number) => {
    if (pct > 80) return 'bg-red-500';
    if (pct >= 50) return 'bg-yellow-500';
    return 'bg-green-500';
  };

  return (
    <div>
      <div className="flex justify-between text-sm mb-1">
        <span className="text-gray-600">{label}</span>
        <span className="font-medium text-gray-900">
          {current.toLocaleString()} / {limit.toLocaleString()}
        </span>
      </div>
      <div className="w-full bg-gray-200 rounded-full h-2.5" role="progressbar" aria-valuenow={current} aria-valuemin={0} aria-valuemax={limit} aria-label={`${label}: ${percent.toFixed(0)}%`}>
        <div
          className={`h-2.5 rounded-full transition-all duration-300 ${getBarColor(percent)}`}
          style={{ width: `${Math.min(percent, 100)}%` }}
        />
      </div>
      <p className="text-xs text-gray-400 mt-0.5">{percent.toFixed(1)}% used</p>
    </div>
  );
}

export function UsageStats() {
  const [stats, setStats] = useState<UsageStatsType | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);

  useEffect(() => {
    const fetchStats = () => {
      getUsageStats()
        .then((data) => {
          setStats(data);
          setError(null);
          setLastUpdated(new Date());
        })
        .catch((err) => setError(err.message));
    };

    fetchStats();
    const interval = setInterval(fetchStats, 30_000);
    return () => clearInterval(interval);
  }, []);

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-5">
        <div className="flex items-center gap-2">
          <svg className="w-5 h-5 text-red-500 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <p className="text-red-700 font-medium">Failed to load usage stats</p>
        </div>
        <p className="text-red-600 text-sm mt-1 ml-7">{error}</p>
      </div>
    );
  }

  if (!stats) {
    return (
      <div className="bg-white rounded-lg border border-gray-200 p-6">
        <div className="animate-pulse flex flex-col gap-4">
          <div className="h-4 bg-gray-200 rounded w-1/3" />
          <div className="h-2.5 bg-gray-200 rounded w-full" />
          <div className="h-4 bg-gray-200 rounded w-1/3" />
          <div className="h-2.5 bg-gray-200 rounded w-full" />
        </div>
      </div>
    );
  }

  const requestPercent = stats.requestLimit > 0
    ? (stats.requestsMade / stats.requestLimit) * 100
    : 0;

  const tokenPercent = stats.tokenLimit > 0
    ? (stats.tokensUsed / stats.tokenLimit) * 100
    : 0;

  const showWarning = requestPercent > 80 || tokenPercent > 80;

  return (
    <div className="bg-white rounded-lg border border-gray-200 p-6 space-y-5">
      {showWarning && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 flex items-start gap-3">
          <svg className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
          <div>
            <p className="text-red-800 font-medium text-sm">High usage warning</p>
            <p className="text-red-600 text-sm mt-0.5">
              {requestPercent > 80 && `Requests at ${requestPercent.toFixed(0)}% of daily limit. `}
              {tokenPercent > 80 && `Tokens at ${tokenPercent.toFixed(0)}% of daily limit. `}
              New requests may be queued when usage reaches 100%.
            </p>
          </div>
        </div>
      )}

      <ProgressBar
        percent={requestPercent}
        label="Requests today"
        current={stats.requestsMade}
        limit={stats.requestLimit}
      />

      <ProgressBar
        percent={tokenPercent}
        label="Tokens today"
        current={stats.tokensUsed}
        limit={stats.tokenLimit}
      />

      {lastUpdated && (
        <p className="text-xs text-gray-400 text-right">
          Last updated: {lastUpdated.toLocaleTimeString()} · Auto-refreshes every 30s
        </p>
      )}
    </div>
  );
}
