'use client';

import { UsageStats } from '@/components/UsageStats';

export default function UsagePage() {
  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Gemini Usage</h1>
        <p className="text-sm text-gray-500 mt-1">
          Daily API request and token consumption against free-tier limits.
        </p>
      </div>

      <UsageStats />
    </div>
  );
}
